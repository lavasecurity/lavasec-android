package com.lavasec.app.presentation.activity

import java.math.BigInteger
import java.time.DateTimeException
import java.time.LocalDate
import java.time.ZoneId
import java.util.Collections

sealed interface ActivityContentState<out T> {
    data object Unavailable : ActivityContentState<Nothing>
    data class Loading<T>(val lastGood: T?) : ActivityContentState<T>
    data class Ready<T>(val value: T) : ActivityContentState<T>
    data object Disabled : ActivityContentState<Nothing>
    data class Failed<T>(val message: String, val lastGood: T?) : ActivityContentState<T> {
        init { require(message.isNotBlank()) }
    }
}

enum class ActivityDecisionUi { Allowed, Blocked }
enum class DomainActionUi { Allow, Block, Remove }
enum class ActivityLogAvailabilityUi { Disabled, Empty, Retained }

/** One domain-free Network Activity row: the exact contract event plus its captured state. */
data class NetworkActivityRowUi(
    val id: Long,
    val timestampMillis: Long,
    val event: com.lavasec.app.foundation.activity.NetworkActivityEvent,
    val capturedState: com.lavasec.app.foundation.activity.NetworkActivityCapturedState,
    val occurrenceCount: Int,
) {
    init {
        require(id > 0L && timestampMillis >= 0L && occurrenceCount > 0)
    }
}

/** Continuation position mirroring the Domain History sentinel contract (same 30+1 paging). */
data class NetworkActivitySentinelKeyUi(
    val cutMaxRowId: Long,
    val cutCapturedAtMillis: Long,
    val cutContinuationEpoch: Long,
    val sourceRevision: Long,
    val finalRowId: Long,
) {
    init {
        require(cutMaxRowId >= 0L && cutCapturedAtMillis >= 0L && cutContinuationEpoch >= 0L)
        require(sourceRevision >= 0L)
        require(finalRowId > 0L && finalRowId <= cutMaxRowId)
    }
}

class NetworkActivitySnapshot(
    rows: List<NetworkActivityRowUi>,
    val nextPageKey: NetworkActivitySentinelKeyUi?,
) {
    val rows: List<NetworkActivityRowUi> = immutableList(rows)
    init {
        require(nextPageKey == null || (this.rows.isNotEmpty() && nextPageKey.finalRowId == this.rows.last().id))
    }

    override fun equals(other: Any?): Boolean = other is NetworkActivitySnapshot &&
        rows == other.rows && nextPageKey == other.nextPageKey
    override fun hashCode(): Int = listOf(rows, nextPageKey).hashCode()
}

data class ActivityDomainQueryUi(
    val decision: ActivityDecisionUi,
    val search: String,
)

data class ActivityQueryControlUi(
    val current: ActivityDomainQueryUi,
    val draft: ActivityDomainQueryUi = current,
)

enum class DomainHistoryReasonUi {
    DefaultAllow, PausedAllow, LocalAllowlist, Blocklist, ThreatGuardrail, InvalidDomain,
    ProtectionUnavailable,
}

data class ActivityDateRangeUi(
    val start: LocalDate,
    val endInclusive: LocalDate,
    val today: LocalDate,
    val zoneId: String,
) {
    init {
        require(!endInclusive.isBefore(start))
        require(!endInclusive.isAfter(today))
        require(zoneId.isNotBlank())
        try { ZoneId.of(zoneId) } catch (failure: DateTimeException) {
            throw IllegalArgumentException("zoneId must be a valid IANA identifier", failure)
        }
        require(zoneId in ZoneId.getAvailableZoneIds())
    }
}

data class ActivityCountSnapshot(val allowed: Long, val blocked: Long) {
    init { require(allowed >= 0L && blocked >= 0L) }

    val total: BigInteger = BigInteger.valueOf(allowed).add(BigInteger.valueOf(blocked))
    val allowedShare: ActivityPercentageUi = share(allowed)
    val blockedShare: ActivityPercentageUi = share(blocked)

    private fun share(value: Long): ActivityPercentageUi {
        if (value == 0L) return ActivityPercentageUi.Zero
        if (BigInteger.valueOf(value) == total) return ActivityPercentageUi.OneHundred
        val scaled = BigInteger.valueOf(value).multiply(HUNDRED)
        if (scaled < total) return ActivityPercentageUi.LessThanOne
        if (scaled > total.multiply(NINETY_NINE)) return ActivityPercentageUi.GreaterThan99
        val quotientAndRemainder = scaled.divideAndRemainder(total)
        val rounded = quotientAndRemainder[0].toInt() +
            if (quotientAndRemainder[1].shiftLeft(1) >= total) 1 else 0
        return ActivityPercentageUi.Rounded(rounded)
    }

    private companion object {
        val HUNDRED = BigInteger.valueOf(100L)
        val NINETY_NINE = BigInteger.valueOf(99L)
    }
}

sealed interface ActivityPercentageUi {
    data object Zero : ActivityPercentageUi
    data object LessThanOne : ActivityPercentageUi
    data class Rounded(val wholePercent: Int) : ActivityPercentageUi {
        init { require(wholePercent in 1..99) }
    }
    data object GreaterThan99 : ActivityPercentageUi
    data object OneHundred : ActivityPercentageUi
}

data class ActivityOverviewSnapshot(
    val dateRange: ActivityDateRangeUi,
    val counts: ActivityCountSnapshot?,
    val localProtectionUptimeMillis: Long?,
    val retainedDomainLogs: ActivityLogAvailabilityUi,
) {
    init { require(localProtectionUptimeMillis == null || localProtectionUptimeMillis >= 0L) }
}

data class TopDomainRowUi(val domain: String, val count: Long) {
    init { require(domain.isNotBlank() && count > 0L) }
}

class TopDomainsSnapshot(
    val dateRange: ActivityDateRangeUi,
    val decision: ActivityDecisionUi,
    val search: String,
    val availability: ActivityLogAvailabilityUi,
    rows: List<TopDomainRowUi>,
) {
    val rows: List<TopDomainRowUi> = immutableList(rows)
    init { require(availability == ActivityLogAvailabilityUi.Retained || this.rows.isEmpty()) }

    override fun equals(other: Any?): Boolean = other is TopDomainsSnapshot &&
        dateRange == other.dateRange && decision == other.decision && search == other.search &&
        availability == other.availability && rows == other.rows
    override fun hashCode(): Int = listOf(dateRange, decision, search, availability, rows).hashCode()
}

data class DomainHistoryRowUi(
    val id: Long,
    val timestampMillis: Long,
    val domain: String,
    val decision: ActivityDecisionUi,
    val reason: DomainHistoryReasonUi,
) {
    init { require(id > 0L && timestampMillis >= 0L && domain.isNotBlank()) }
}

data class DomainHistorySentinelKeyUi(
    val decision: ActivityDecisionUi,
    val search: String,
    val cutMaxRowId: Long,
    val cutCapturedAtMillis: Long,
    val cutContinuationEpoch: Long,
    val sourceRevision: Long,
    val sourcePolicyRevision: Long,
    val finalRowId: Long,
) {
    init {
        require(cutMaxRowId >= 0L && cutCapturedAtMillis >= 0L && cutContinuationEpoch >= 0L)
        require(sourceRevision >= 0L && sourcePolicyRevision >= 0L)
        require(finalRowId > 0L && finalRowId <= cutMaxRowId)
    }
}

class DomainHistorySnapshot(
    val decision: ActivityDecisionUi,
    val search: String,
    rows: List<DomainHistoryRowUi>,
    val nextPageKey: DomainHistorySentinelKeyUi?,
) {
    val rows: List<DomainHistoryRowUi> = immutableList(rows)
    init {
        require(nextPageKey == null || (this.rows.isNotEmpty() && nextPageKey.finalRowId == this.rows.last().id))
        require(nextPageKey == null || (nextPageKey.decision == decision && nextPageKey.search == search))
    }

    override fun equals(other: Any?): Boolean = other is DomainHistorySnapshot &&
        decision == other.decision && search == other.search && rows == other.rows &&
        nextPageKey == other.nextPageKey
    override fun hashCode(): Int = listOf(decision, search, rows, nextPageKey).hashCode()
}

enum class DomainActionChangeGroupUi { BlockedDomains, AllowedExceptions }
enum class DomainActionChangeKindUi { Added, Removed }

data class DomainActionChangeUi(
    val group: DomainActionChangeGroupUi,
    val kind: DomainActionChangeKindUi,
    val domain: String,
) { init { require(domain.isNotBlank()) } }

class DomainActionReviewUi(
    val id: Long,
    val domain: String,
    val action: DomainActionUi,
    changes: List<DomainActionChangeUi>,
) {
    val changes: List<DomainActionChangeUi> = immutableList(changes)
    val changeCount: Int get() = changes.size
    val requiresAllowWarning: Boolean get() = action == DomainActionUi.Allow
    init { require(id > 0L && domain.isNotBlank()) }

    override fun equals(other: Any?): Boolean = other is DomainActionReviewUi &&
        id == other.id && domain == other.domain && action == other.action && changes == other.changes
    override fun hashCode(): Int = listOf(id, domain, action, changes).hashCode()
}

private fun <T> immutableList(values: List<T>): List<T> =
    Collections.unmodifiableList(ArrayList(values))

sealed interface DomainActionUiState {
    data class Presenting(val review: DomainActionReviewUi) : DomainActionUiState
    data class Handoff(val review: DomainActionReviewUi) : DomainActionUiState
    data object Idle : DomainActionUiState
    data class ReviewPending(val review: DomainActionReviewUi) : DomainActionUiState
    data class Applying(val review: DomainActionReviewUi) : DomainActionUiState
    data class ApplyFailed(val review: DomainActionReviewUi, val message: String) : DomainActionUiState {
        init { require(message.isNotBlank()) }
    }
}

enum class DomainActionValidationUi {
    InvalidDomain, AlreadyAllowed, AlreadyBlocked, AllowedLimitReached,
    BlockedLimitReached, RuleNotPresent, RuleRejected,
}

sealed interface DomainActionNoticeContentUi {
    data object UnsavedFilterEdits : DomainActionNoticeContentUi
    data class ValidationFailed(val reason: DomainActionValidationUi) : DomainActionNoticeContentUi
    data class AuthenticationFailed(val message: String) : DomainActionNoticeContentUi { init { require(message.isNotBlank()) } }
    data class ReviewRejected(val message: String) : DomainActionNoticeContentUi { init { require(message.isNotBlank()) } }
    data class ApplyRejected(val message: String) : DomainActionNoticeContentUi { init { require(message.isNotBlank()) } }
    data class Failed(val message: String) : DomainActionNoticeContentUi { init { require(message.isNotBlank()) } }
    data object Busy : DomainActionNoticeContentUi
    data object ReviewUnavailable : DomainActionNoticeContentUi
    data object Superseded : DomainActionNoticeContentUi
}

data class DomainActionNoticeUi(val id: Long, val content: DomainActionNoticeContentUi) {
    init { require(id > 0L) }
}

data class ActivityUiState(
    val overview: ActivityContentState<ActivityOverviewSnapshot>,
    val topDomains: ActivityContentState<TopDomainsSnapshot>,
    val domainHistory: ActivityContentState<DomainHistorySnapshot>,
    val retainedDomainLogs: ActivityContentState<Boolean>,
    val historyLoadingMore: Boolean,
    val domainAction: DomainActionUiState,
    val domainActionNotice: DomainActionNoticeUi?,
    val topDomainsQuery: ActivityQueryControlUi? = null,
    val domainHistoryQuery: ActivityQueryControlUi? = null,
    /**
     * The domain-free Network Activity surface shown under Settings → Advanced. It rides the
     * same Activity state machine (exact revision + 30-row paging) as Domain History; the
     * unavailable default keeps pre-onboarding hosts honest.
     */
    val networkActivity: ActivityContentState<NetworkActivitySnapshot> = ActivityContentState.Unavailable,
    val networkLoadingMore: Boolean = false,
) {
    companion object {
        val unavailable = ActivityUiState(
            overview = ActivityContentState.Unavailable,
            topDomains = ActivityContentState.Unavailable,
            domainHistory = ActivityContentState.Unavailable,
            retainedDomainLogs = ActivityContentState.Unavailable,
            historyLoadingMore = false,
            domainAction = DomainActionUiState.Idle,
            domainActionNotice = null,
            topDomainsQuery = null,
            domainHistoryQuery = null,
            networkActivity = ActivityContentState.Unavailable,
            networkLoadingMore = false,
        )
    }
}
