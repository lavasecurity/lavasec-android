package com.lavasec.app.product.activity

import com.lavasec.app.foundation.activity.ActivityDateRangePolicy
import com.lavasec.app.foundation.activity.NetworkActivityEntry
import com.lavasec.app.foundation.filter.FilterDecisionReason
import com.lavasec.app.product.LOCAL_LOG_PAGE_SIZE
import com.lavasec.app.product.PageToken
import com.lavasec.app.product.Revisioned
import com.lavasec.app.product.StablePage
import com.lavasec.app.product.StableReadCut
import java.time.DateTimeException
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.Collections
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf

private fun <T> immutableListSnapshot(values: List<T>): List<T> =
    Collections.unmodifiableList(ArrayList(values))

/** Inclusive local-day range used by the Activity digest and Top Domains only. */
data class ActivityDateLens(
    val startDayMillis: Long,
    val endDayMillis: Long,
    /** IANA zone that produced both exact local-day starts. */
    val zoneId: String,
    /** Current local day from the same clock/zone snapshot, used for bounds and default Today. */
    val today: LocalDate,
) {
    init {
        require(startDayMillis >= 0L)
        require(endDayMillis >= startDayMillis)
        require(zoneId.isNotBlank())
        val parsedZone = try {
            ZoneId.of(zoneId)
        } catch (failure: DateTimeException) {
            throw IllegalArgumentException("zoneId must be a valid IANA identifier", failure)
        }
        require(zoneId in ZoneId.getAvailableZoneIds()) {
            "zoneId must be a valid IANA identifier"
        }
        val startDate = Instant.ofEpochMilli(startDayMillis).atZone(parsedZone).toLocalDate()
        val endDate = Instant.ofEpochMilli(endDayMillis).atZone(parsedZone).toLocalDate()
        require(startDate.atStartOfDay(parsedZone).toInstant().toEpochMilli() == startDayMillis) {
            "startDayMillis must be an exact local-day start"
        }
        require(endDate.atStartOfDay(parsedZone).toInstant().toEpochMilli() == endDayMillis) {
            "endDayMillis must be an exact local-day start"
        }
        require(ActivityDateRangePolicy.isSelectable(startDate, today)) {
            "start day is outside the selectable Activity window"
        }
        require(ActivityDateRangePolicy.isSelectable(endDate, today)) {
            "end day is outside the selectable Activity window"
        }
    }
}

enum class DomainDecision {
    ALLOWED,
    BLOCKED,
}

/** Closed retained-log state captured with every Domain History or Top Domains read. */
enum class DomainHistoryAvailability {
    DISABLED,
    EMPTY,
    RETAINED,
}

data class ActivitySummaryQuery(
    val dateLens: ActivityDateLens,
    val topDomainDecision: DomainDecision = DomainDecision.BLOCKED,
    val topDomainSearch: String = "",
)

data class ActivityDigest(
    val allowedCount: Long,
    val blockedCount: Long,
    val localProtectionUptimeMillis: Long,
) {
    init {
        require(allowedCount >= 0L)
        require(blockedCount >= 0L)
        require(localProtectionUptimeMillis >= 0L)
    }
}

data class TopDomainRow(
    val domain: String,
    val count: Long,
) {
    init {
        require(domain.isNotBlank())
        require(count > 0L)
    }
}

class ActivitySummary(
    val digest: ActivityDigest,
    topDomains: List<TopDomainRow>,
    /** Exact date lens used for both the digest and Top Domains read. */
    val dateLens: ActivityDateLens,
    /** Authoritative Domain Logs data generation used for Top Domains. */
    val topDomainsDomainRevision: Long,
    /** Authoritative logging-policy generation used for Top Domains. */
    val topDomainsPolicyRevision: Long,
    /** Unfiltered retained-log state captured with the filtered Top Domains payload. */
    val topDomainsAvailability: DomainHistoryAvailability,
    /** Clock value used by the repository read that produced [digest]. */
    val capturedAtMillis: Long = 0L,
    /** Last persisted heartbeat included in live uptime, or null when no session is open. */
    val liveUptimeEvidenceThroughMillis: Long? = null,
    /** Durable counts/uptime revision captured with this exact summary snapshot. */
    val summaryCountRevision: Long = 0L,
    /** Decision filter that produced [topDomains], including while this value is last-good. */
    val topDomainsDecision: DomainDecision = DomainDecision.BLOCKED,
    /** Normalized search filter that produced [topDomains]. */
    val topDomainsSearch: String = "",
    val countsAndUptimeAvailable: Boolean = true,
) {
    val topDomains: List<TopDomainRow> = immutableListSnapshot(topDomains)

    init {
        require(capturedAtMillis >= 0L)
        require(summaryCountRevision >= 0L)
        require(topDomainsDomainRevision >= 0L)
        require(topDomainsPolicyRevision >= 0L)
        require(
            liveUptimeEvidenceThroughMillis == null ||
                liveUptimeEvidenceThroughMillis in 0L..capturedAtMillis
        ) {
            "live uptime evidence must not be newer than its captured read"
        }
        require(countsAndUptimeAvailable || digest == ActivityDigest(0L, 0L, 0L)) {
            "disabled counts and uptime cannot expose retained values"
        }
        require(countsAndUptimeAvailable || liveUptimeEvidenceThroughMillis == null) {
            "disabled counts and uptime cannot expose live uptime evidence"
        }
        require(
            topDomainsAvailability == DomainHistoryAvailability.RETAINED || topDomains.isEmpty(),
        ) {
            "disabled or empty Top Domains cannot expose retained rows"
        }
    }

    fun copy(
        digest: ActivityDigest = this.digest,
        topDomains: List<TopDomainRow> = this.topDomains,
        dateLens: ActivityDateLens = this.dateLens,
        topDomainsDomainRevision: Long = this.topDomainsDomainRevision,
        topDomainsPolicyRevision: Long = this.topDomainsPolicyRevision,
        topDomainsAvailability: DomainHistoryAvailability = this.topDomainsAvailability,
        capturedAtMillis: Long = this.capturedAtMillis,
        liveUptimeEvidenceThroughMillis: Long? = this.liveUptimeEvidenceThroughMillis,
        summaryCountRevision: Long = this.summaryCountRevision,
        topDomainsDecision: DomainDecision = this.topDomainsDecision,
        topDomainsSearch: String = this.topDomainsSearch,
        countsAndUptimeAvailable: Boolean = this.countsAndUptimeAvailable,
    ): ActivitySummary = ActivitySummary(
        digest = digest,
        topDomains = topDomains,
        dateLens = dateLens,
        topDomainsDomainRevision = topDomainsDomainRevision,
        topDomainsPolicyRevision = topDomainsPolicyRevision,
        topDomainsAvailability = topDomainsAvailability,
        capturedAtMillis = capturedAtMillis,
        liveUptimeEvidenceThroughMillis = liveUptimeEvidenceThroughMillis,
        summaryCountRevision = summaryCountRevision,
        topDomainsDecision = topDomainsDecision,
        topDomainsSearch = topDomainsSearch,
        countsAndUptimeAvailable = countsAndUptimeAvailable,
    )

    operator fun component1(): ActivityDigest = digest

    operator fun component2(): List<TopDomainRow> = topDomains

    operator fun component3(): ActivityDateLens = dateLens

    operator fun component4(): Long = topDomainsDomainRevision

    operator fun component5(): Long = topDomainsPolicyRevision

    operator fun component6(): DomainHistoryAvailability = topDomainsAvailability

    operator fun component7(): Long = capturedAtMillis

    operator fun component8(): Long? = liveUptimeEvidenceThroughMillis

    operator fun component9(): Long = summaryCountRevision

    operator fun component10(): DomainDecision = topDomainsDecision

    operator fun component11(): String = topDomainsSearch

    operator fun component12(): Boolean = countsAndUptimeAvailable

    val query: ActivitySummaryQuery
        get() = ActivitySummaryQuery(
            dateLens = dateLens,
            topDomainDecision = topDomainsDecision,
            topDomainSearch = topDomainsSearch,
        )

    override fun equals(other: Any?): Boolean =
        this === other ||
            (
                other is ActivitySummary &&
                    digest == other.digest &&
                    topDomains == other.topDomains &&
                    dateLens == other.dateLens &&
                    topDomainsDomainRevision == other.topDomainsDomainRevision &&
                    topDomainsPolicyRevision == other.topDomainsPolicyRevision &&
                    topDomainsAvailability == other.topDomainsAvailability &&
                    capturedAtMillis == other.capturedAtMillis &&
                    liveUptimeEvidenceThroughMillis == other.liveUptimeEvidenceThroughMillis &&
                    summaryCountRevision == other.summaryCountRevision &&
                    topDomainsDecision == other.topDomainsDecision &&
                    topDomainsSearch == other.topDomainsSearch &&
                    countsAndUptimeAvailable == other.countsAndUptimeAvailable
            )

    override fun hashCode(): Int {
        var result = digest.hashCode()
        result = 31 * result + topDomains.hashCode()
        result = 31 * result + dateLens.hashCode()
        result = 31 * result + topDomainsDomainRevision.hashCode()
        result = 31 * result + topDomainsPolicyRevision.hashCode()
        result = 31 * result + topDomainsAvailability.hashCode()
        result = 31 * result + capturedAtMillis.hashCode()
        result = 31 * result + (liveUptimeEvidenceThroughMillis?.hashCode() ?: 0)
        result = 31 * result + summaryCountRevision.hashCode()
        result = 31 * result + topDomainsDecision.hashCode()
        result = 31 * result + topDomainsSearch.hashCode()
        result = 31 * result + countsAndUptimeAvailable.hashCode()
        return result
    }

    override fun toString(): String =
        "ActivitySummary(digest=$digest, topDomains=$topDomains, dateLens=$dateLens, " +
            "topDomainsDomainRevision=$topDomainsDomainRevision, " +
            "topDomainsPolicyRevision=$topDomainsPolicyRevision, " +
            "topDomainsAvailability=$topDomainsAvailability, " +
            "capturedAtMillis=$capturedAtMillis, " +
            "liveUptimeEvidenceThroughMillis=$liveUptimeEvidenceThroughMillis, " +
            "summaryCountRevision=$summaryCountRevision, topDomainsDecision=$topDomainsDecision, " +
            "topDomainsSearch=$topDomainsSearch, " +
            "countsAndUptimeAvailable=$countsAndUptimeAvailable)"
}

/** Exact request and typed outcome captured at one authoritative summary revision. */
data class ActivitySummaryRead(
    val query: ActivitySummaryQuery,
    val topDomainsDomainRevision: Long,
    val topDomainsPolicyRevision: Long,
    val topDomainsAvailability: DomainHistoryAvailability,
    val result: ProductReadResult<ActivitySummary>,
) {
    init {
        require(topDomainsDomainRevision >= 0L)
        require(topDomainsPolicyRevision >= 0L)
        when (result) {
            is ProductReadResult.Available -> {
                require(result.value.dateLens == query.dateLens) {
                    "available summary payload must match its exact date lens"
                }
                require(result.value.topDomainsDecision == query.topDomainDecision) {
                    "available summary payload must match its exact Top Domains decision"
                }
                require(result.value.topDomainsSearch == query.topDomainSearch) {
                    "available summary payload must match its exact Top Domains search"
                }
                require(result.value.topDomainsDomainRevision == topDomainsDomainRevision) {
                    "available summary payload must match its Domain Logs revision"
                }
                require(result.value.topDomainsPolicyRevision == topDomainsPolicyRevision) {
                    "available summary payload must match its logging-policy revision"
                }
                require(result.value.topDomainsAvailability == topDomainsAvailability) {
                    "available summary payload must match its retained-log availability"
                }
            }

            ProductReadResult.Disabled -> require(
                topDomainsAvailability == DomainHistoryAvailability.DISABLED,
            ) {
                "a disabled summary read requires disabled Top Domains availability"
            }
        }
    }
}

interface StableActivityRow {
    val id: Long
    val timestampMillis: Long
}

data class DomainHistoryRow(
    override val id: Long,
    override val timestampMillis: Long,
    val domain: String,
    val decision: DomainDecision,
    val reason: FilterDecisionReason,
) : StableActivityRow {
    init {
        require(id > 0L)
        require(timestampMillis >= 0L)
        require(domain.isNotBlank())
        val reasonDecision = when (reason) {
            FilterDecisionReason.DEFAULT_ALLOW,
            FilterDecisionReason.PAUSED_ALLOW,
            FilterDecisionReason.LOCAL_ALLOWLIST,
            -> DomainDecision.ALLOWED

            FilterDecisionReason.BLOCKLIST,
            FilterDecisionReason.THREAT_GUARDRAIL,
            FilterDecisionReason.INVALID_DOMAIN,
            FilterDecisionReason.PROTECTION_UNAVAILABLE,
            -> DomainDecision.BLOCKED
        }
        require(decision == reasonDecision) {
            "Domain History decision must agree with its persisted reason"
        }
    }
}

data class NetworkActivityRow(
    val entry: NetworkActivityEntry,
) : StableActivityRow {
    override val id: Long
        get() = entry.id

    override val timestampMillis: Long
        get() = entry.latestOccurrenceEpochMillis

    init {
        require(id > 0L)
        require(timestampMillis >= 0L)
        require(entry.firstOccurrenceEpochMillis >= 0L)
    }
}

data class DomainHistoryQuery(
    val decision: DomainDecision = DomainDecision.BLOCKED,
    val search: String = "",
)

/**
 * A port adapter must query [fetchLimit] rows. It emits at most [pageSize] rows and represents
 * the extra (31st) sentinel by returning a non-null [StablePage.next].
 */
data class DomainHistoryPageRequest(
    val query: DomainHistoryQuery = DomainHistoryQuery(),
    val token: PageToken? = null,
    val pageSize: Int = LOCAL_LOG_PAGE_SIZE,
) {
    init {
        require(pageSize == LOCAL_LOG_PAGE_SIZE)
    }

    val fetchLimit: Int = pageSize + 1
}

/** Exact query plus typed result captured at one authoritative Domain Logs revision. */
data class DomainHistoryRead(
    val query: DomainHistoryQuery,
    val policyRevision: Long,
    val availability: DomainHistoryAvailability,
    val result: ProductReadResult<StablePage<DomainHistoryRow>>,
) {
    init {
        require(policyRevision >= 0L)
        when (result) {
            is ProductReadResult.Available -> {
                require(availability != DomainHistoryAvailability.DISABLED) {
                    "an available Domain History page cannot report disabled availability"
                }
                require(result.value.rows.all { row -> row.decision == query.decision }) {
                    "available Domain History rows must match the exact decision query"
                }
                if (availability == DomainHistoryAvailability.EMPTY) {
                    require(result.value.rows.isEmpty() && result.value.next == null) {
                        "empty Domain History availability requires an empty terminal page"
                    }
                }
            }

            ProductReadResult.Disabled -> require(
                availability == DomainHistoryAvailability.DISABLED,
            ) {
                "a disabled Domain History result requires disabled availability"
            }
        }
    }
}

/** Network Activity uses the same 30-visible-plus-one-sentinel paging contract. */
data class NetworkActivityPageRequest(
    val token: PageToken? = null,
    val pageSize: Int = LOCAL_LOG_PAGE_SIZE,
) {
    init {
        require(pageSize == LOCAL_LOG_PAGE_SIZE)
    }

    val fetchLimit: Int = pageSize + 1
}

private enum class ProductPageKind {
    DOMAIN_HISTORY,
    NETWORK_ACTIVITY,
}

class ProductPagedRows<T : StableActivityRow> private constructor(
    rows: List<T>,
    val next: PageToken?,
    val cut: StableReadCut,
    val sourceRevision: Long,
    val sourcePolicyRevision: Long?,
    val historyQuery: DomainHistoryQuery?,
    kind: ProductPageKind,
) {
    val rows: List<T> = immutableListSnapshot(rows)

    init {
        require(sourceRevision >= 0L)
        require(sourcePolicyRevision == null || sourcePolicyRevision >= 0L)
        when (kind) {
            ProductPageKind.DOMAIN_HISTORY -> require(
                sourcePolicyRevision != null && historyQuery != null,
            ) {
                "Domain History pages require exact policy and query provenance"
            }

            ProductPageKind.NETWORK_ACTIVITY -> require(
                sourcePolicyRevision == null && historyQuery == null,
            ) {
                "Network Activity pages cannot carry Domain History provenance"
            }
        }
        require(next == null || next.cut == cut) {
            "next page must preserve the published frozen read cut"
        }
    }

    companion object {
        @JvmStatic
        fun domainHistory(
            rows: List<DomainHistoryRow>,
            next: PageToken?,
            cut: StableReadCut,
            sourceRevision: Long,
            sourcePolicyRevision: Long,
            historyQuery: DomainHistoryQuery,
        ): ProductPagedRows<DomainHistoryRow> = ProductPagedRows(
            rows = rows,
            next = next,
            cut = cut,
            sourceRevision = sourceRevision,
            sourcePolicyRevision = sourcePolicyRevision,
            historyQuery = historyQuery,
            kind = ProductPageKind.DOMAIN_HISTORY,
        )

        @JvmStatic
        fun networkActivity(
            rows: List<NetworkActivityRow>,
            next: PageToken?,
            cut: StableReadCut,
            sourceRevision: Long,
        ): ProductPagedRows<NetworkActivityRow> = ProductPagedRows(
            rows = rows,
            next = next,
            cut = cut,
            sourceRevision = sourceRevision,
            sourcePolicyRevision = null,
            historyQuery = null,
            kind = ProductPageKind.NETWORK_ACTIVITY,
        )

    }

    operator fun component1(): List<T> = rows

    operator fun component2(): PageToken? = next

    operator fun component3(): StableReadCut = cut

    operator fun component4(): Long = sourceRevision

    operator fun component5(): Long? = sourcePolicyRevision

    operator fun component6(): DomainHistoryQuery? = historyQuery

    override fun equals(other: Any?): Boolean =
        this === other ||
            (
                other is ProductPagedRows<*> &&
                    rows == other.rows &&
                    next == other.next &&
                    cut == other.cut &&
                    sourceRevision == other.sourceRevision &&
                    sourcePolicyRevision == other.sourcePolicyRevision &&
                    historyQuery == other.historyQuery
            )

    override fun hashCode(): Int {
        var result = rows.hashCode()
        result = 31 * result + (next?.hashCode() ?: 0)
        result = 31 * result + cut.hashCode()
        result = 31 * result + sourceRevision.hashCode()
        result = 31 * result + (sourcePolicyRevision?.hashCode() ?: 0)
        result = 31 * result + (historyQuery?.hashCode() ?: 0)
        return result
    }

    override fun toString(): String =
        "ProductPagedRows(rows=$rows, next=$next, cut=$cut, " +
            "sourceRevision=$sourceRevision, sourcePolicyRevision=$sourcePolicyRevision, " +
            "historyQuery=$historyQuery)"
}

sealed interface ProductReadResult<out T> {
    data class Available<T>(val value: T) : ProductReadResult<T>

    data object Disabled : ProductReadResult<Nothing>
}

sealed interface ProductLoadState<out T> {
    data class Loading<T>(val lastGood: T?) : ProductLoadState<T>

    data class Ready<T>(val value: T) : ProductLoadState<T>

    data object Disabled : ProductLoadState<Nothing>

    data class Failed<T>(
        val message: String,
        val lastGood: T?,
    ) : ProductLoadState<T>
}

fun <T> ProductLoadState<T>.lastGoodOrNull(): T? = when (this) {
    is ProductLoadState.Loading -> lastGood
    is ProductLoadState.Ready -> value
    ProductLoadState.Disabled -> null
    is ProductLoadState.Failed -> lastGood
}

data class ProductActivityRevisionSignal(
    val token: Long,
    /**
     * Sticky logging-policy generation. Coalescing may replace [token], but it must preserve this
     * generation so an enable or empty disable cannot be erased by a later data-only commit.
     */
    val policyRevision: Long = 0L,
) {
    init {
        require(token >= 0L)
        require(policyRevision >= 0L)
    }
}

/** Pure data boundary; Android repository and lifecycle adapters are checkpoint-9 concerns. */
interface ProductActivityPort {
    /**
     * Coalescible notification that one or more authoritative revisions may have advanced.
     *
     * Consumers still read and compare repository revisions before reloading. The default emits
     * once so pure/fake ports retain the existing initial-synchronization contract.
     */
    val revisionSignals: Flow<ProductActivityRevisionSignal>
        get() = flowOf(ProductActivityRevisionSignal(0L))

    suspend fun summaryCountRevision(): Long

    suspend fun domainLogRevision(): Long

    suspend fun networkRevision(): Long

    suspend fun loadSummary(
        query: ActivitySummaryQuery,
        topDomainsLimit: Int,
    ): Revisioned<ActivitySummaryRead>

    suspend fun loadDomainHistory(
        request: DomainHistoryPageRequest,
    ): Revisioned<DomainHistoryRead>

    suspend fun loadNetworkActivity(
        request: NetworkActivityPageRequest,
    ): Revisioned<ProductReadResult<StablePage<NetworkActivityRow>>>
}
