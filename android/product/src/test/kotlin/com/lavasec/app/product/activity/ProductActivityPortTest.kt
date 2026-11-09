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
import com.lavasec.app.product.StablePage
import com.lavasec.app.product.StableReadCut
import java.time.LocalDate
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

class ProductActivityPortTest {
    @Test
    fun `Activity date lens rejects a non-IANA zone identifier`() {
        assertThrows(IllegalArgumentException::class.java) {
            ActivityDateLens(
                startDayMillis = 0L,
                endDayMillis = 0L,
                zoneId = "Mars/Lava",
                today = LocalDate.of(2026, 8, 1),
            )
        }

        val fixedOffset = ZoneId.of("+09:00")
        val today = LocalDate.of(2026, 8, 1)
        val dayStart = today.atStartOfDay(fixedOffset).toInstant().toEpochMilli()
        assertThrows(IllegalArgumentException::class.java) {
            ActivityDateLens(
                startDayMillis = dayStart,
                endDayMillis = dayStart,
                zoneId = fixedOffset.id,
                today = today,
            )
        }
    }

    @Test
    fun `Activity date lens requires exact local-day starts across DST`() {
        val zone = ZoneId.of("America/New_York")
        val today = LocalDate.of(2026, 3, 9)
        val start = LocalDate.of(2026, 3, 8).atStartOfDay(zone).toInstant().toEpochMilli()
        val end = today.atStartOfDay(zone).toInstant().toEpochMilli()

        val lens = ActivityDateLens(start, end, zone.id, today)

        assertEquals(start, lens.startDayMillis)
        assertEquals(end, lens.endDayMillis)
        assertThrows(IllegalArgumentException::class.java) {
            ActivityDateLens(start + 1L, end, zone.id, today)
        }
        assertThrows(IllegalArgumentException::class.java) {
            ActivityDateLens(start, end + 1L, zone.id, today)
        }
    }

    @Test
    fun `Activity date lens rejects a future local day`() {
        val zone = ZoneId.of("Asia/Tokyo")
        val today = LocalDate.of(2026, 8, 1)
        val tomorrow = today.plusDays(1L).atStartOfDay(zone).toInstant().toEpochMilli()

        assertThrows(IllegalArgumentException::class.java) {
            ActivityDateLens(tomorrow, tomorrow, zone.id, today)
        }
    }

    @Test
    fun `Activity date lens rejects the day before its 24-month window`() {
        val zone = ZoneId.of("Europe/London")
        val today = LocalDate.of(2026, 8, 1)
        val tooOld = LocalDate.of(2024, 8, 31).atStartOfDay(zone).toInstant().toEpochMilli()

        assertThrows(IllegalArgumentException::class.java) {
            ActivityDateLens(tooOld, tooOld, zone.id, today)
        }
    }

    @Test
    fun `history and network requests reserve one row for the paging sentinel`() {
        val history = DomainHistoryPageRequest()
        val network = NetworkActivityPageRequest()

        assertEquals(LOCAL_LOG_PAGE_SIZE, history.pageSize)
        assertEquals(31, history.fetchLimit)
        assertNull(history.token)
        assertEquals(LOCAL_LOG_PAGE_SIZE, network.pageSize)
        assertEquals(31, network.fetchLimit)
        assertNull(network.token)
    }

    @Test
    fun `typed load failure retains the last confirmed value`() {
        val lastGood = ProductPagedRows.domainHistory(
            rows = listOf(domainRow(id = 9L)),
            next = null,
            cut = StableReadCut(maxRowId = 9L, capturedAtMillis = 1_000L),
            sourceRevision = 4L,
            sourcePolicyRevision = 2L,
            historyQuery = DomainHistoryQuery(),
        )

        val state: ProductLoadState<ProductPagedRows<DomainHistoryRow>> =
            ProductLoadState.Failed(message = "database unavailable", lastGood = lastGood)

        assertEquals(lastGood, state.lastGoodOrNull())
    }

    @Test
    fun `network row exposes only the closed domain-free foundation entry`() {
        val entry = networkEntry(
            id = 7L,
            firstOccurrenceEpochMillis = 800L,
            latestOccurrenceEpochMillis = 900L,
        )

        val row = NetworkActivityRow(entry)

        assertEquals(7L, row.id)
        assertEquals(900L, row.timestampMillis)
        assertEquals(entry, row.entry)
        assertEquals(row, row.copy())
    }

    @Test
    fun `network row rejects unstable identity and negative occurrence timestamps`() {
        assertThrows(IllegalArgumentException::class.java) {
            NetworkActivityRow(networkEntry(id = 0L))
        }
        assertThrows(IllegalArgumentException::class.java) {
            NetworkActivityRow(
                networkEntry(
                    id = 1L,
                    firstOccurrenceEpochMillis = -2L,
                    latestOccurrenceEpochMillis = -1L,
                ),
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            NetworkActivityRow(
                networkEntry(
                    id = 1L,
                    firstOccurrenceEpochMillis = -1L,
                    latestOccurrenceEpochMillis = 0L,
                ),
            )
        }
    }

    @Test
    fun `summary carries the exact query that produced its digest and Top Domains`() {
        val lens = activityLens()
        val summary = ActivitySummary(
            digest = ActivityDigest(allowedCount = 1L, blockedCount = 2L, localProtectionUptimeMillis = 3L),
            topDomains = listOf(TopDomainRow("allowed.example", 4L)),
            dateLens = lens,
            capturedAtMillis = 5L,
            topDomainsDomainRevision = 7L,
            topDomainsPolicyRevision = 11L,
            topDomainsAvailability = DomainHistoryAvailability.RETAINED,
            topDomainsDecision = DomainDecision.ALLOWED,
            topDomainsSearch = "allowed",
        )

        assertEquals(5L, summary.capturedAtMillis)
        assertEquals(
            ActivitySummaryQuery(lens, DomainDecision.ALLOWED, "allowed"),
            summary.query,
        )
        assertThrows(IllegalArgumentException::class.java) {
            summary.copy(capturedAtMillis = -1L)
        }
    }

    @Test
    fun `disabled summary counts cannot expose live uptime evidence`() {
        assertThrows(IllegalArgumentException::class.java) {
            ActivitySummary(
                digest = ActivityDigest(0L, 0L, 0L),
                topDomains = emptyList(),
                dateLens = activityLens(),
                capturedAtMillis = 10L,
                liveUptimeEvidenceThroughMillis = 9L,
                topDomainsDomainRevision = 0L,
                topDomainsPolicyRevision = 0L,
                topDomainsAvailability = DomainHistoryAvailability.EMPTY,
                countsAndUptimeAvailable = false,
            )
        }
    }

    @Test
    fun `domain history row preserves the exact persisted decision reason`() {
        val paused = DomainHistoryRow(
            id = 10L,
            timestampMillis = 900L,
            domain = "paused.example",
            decision = DomainDecision.ALLOWED,
            reason = FilterDecisionReason.PAUSED_ALLOW,
        )

        assertEquals(FilterDecisionReason.PAUSED_ALLOW, paused.reason)
    }

    @Test
    fun `protection unavailable history is blocked and rejects an allowed row`() {
        val failedSafe = DomainHistoryRow(
            id = 11L,
            timestampMillis = 901L,
            domain = "failed-safe.example",
            decision = DomainDecision.BLOCKED,
            reason = FilterDecisionReason.PROTECTION_UNAVAILABLE,
        )

        assertEquals(DomainDecision.BLOCKED, failedSafe.decision)
        assertEquals(FilterDecisionReason.PROTECTION_UNAVAILABLE, failedSafe.reason)
        assertThrows(IllegalArgumentException::class.java) {
            failedSafe.copy(decision = DomainDecision.ALLOWED)
        }
    }

    @Test
    fun `activity summary owns an unmodifiable Top Domains snapshot and keeps value semantics`() {
        val callerRows = mutableListOf(TopDomainRow("first.example", 1L))
        val summary = ActivitySummary(
            digest = ActivityDigest(allowedCount = 1L, blockedCount = 0L, localProtectionUptimeMillis = 2L),
            topDomains = callerRows,
            dateLens = activityLens(),
            capturedAtMillis = 3L,
            topDomainsDomainRevision = 4L,
            topDomainsPolicyRevision = 2L,
            topDomainsAvailability = DomainHistoryAvailability.RETAINED,
        )

        callerRows += TopDomainRow("late.example", 2L)

        assertEquals(listOf(TopDomainRow("first.example", 1L)), summary.topDomains)
        assertThrows(UnsupportedOperationException::class.java) {
            (summary.topDomains as MutableList<TopDomainRow>) += TopDomainRow("external.example", 3L)
        }
        assertEquals(
            ActivitySummary(
                digest = ActivityDigest(1L, 0L, 2L),
                topDomains = listOf(TopDomainRow("first.example", 1L)),
                dateLens = activityLens(),
                capturedAtMillis = 3L,
                topDomainsDomainRevision = 4L,
                topDomainsPolicyRevision = 2L,
                topDomainsAvailability = DomainHistoryAvailability.RETAINED,
            ),
            summary,
        )
        assertEquals(summary, summary.copy())
    }

    @Test
    fun `paged rows own an unmodifiable snapshot and keep value semantics`() {
        val callerRows = mutableListOf(domainRow(id = 9L))
        val cut = StableReadCut(maxRowId = 9L, capturedAtMillis = 1_000L)
        val page = ProductPagedRows.domainHistory(
            rows = callerRows,
            next = null,
            cut = cut,
            sourceRevision = 4L,
            sourcePolicyRevision = 2L,
            historyQuery = DomainHistoryQuery(search = "example"),
        )

        callerRows += domainRow(id = 8L)

        assertEquals(listOf(9L), page.rows.map { it.id })
        assertThrows(UnsupportedOperationException::class.java) {
            (page.rows as MutableList<DomainHistoryRow>) += domainRow(id = 7L)
        }
        assertEquals(
            ProductPagedRows.domainHistory(
                rows = listOf(domainRow(id = 9L)),
                next = null,
                cut = cut,
                sourceRevision = 4L,
                sourcePolicyRevision = 2L,
                historyQuery = DomainHistoryQuery(search = "example"),
            ),
            page,
        )
        assertEquals(2L, page.sourcePolicyRevision)
        assertNull(
            ProductPagedRows.networkActivity(
                rows = listOf(NetworkActivityRow(networkEntry(id = 9L))),
                next = null,
                cut = cut,
                sourceRevision = 4L,
            ).sourcePolicyRevision,
        )
        assertThrows(IllegalArgumentException::class.java) {
            ProductPagedRows.domainHistory(
                rows = listOf(domainRow(id = 9L)),
                next = null,
                cut = cut,
                sourceRevision = 4L,
                sourcePolicyRevision = -1L,
                historyQuery = DomainHistoryQuery(search = "example"),
            )
        }
    }

    @Test
    fun `paged rows expose no generic public construction or copy path`() {
        assertFalse(
            ProductPagedRows::class.java.constructors.any { constructor ->
                !constructor.isSynthetic
            },
        )
        assertFalse(ProductPagedRows::class.java.methods.any { method -> method.name == "copy" })
    }

    @Test
    fun `typed page factories bind provenance to their row kind`() {
        val cut = StableReadCut(maxRowId = 9L, capturedAtMillis = 1_000L)
        val query = DomainHistoryQuery(search = "example")

        val history = ProductPagedRows.domainHistory(
            rows = listOf(domainRow(id = 9L)),
            next = null,
            cut = cut,
            sourceRevision = 4L,
            sourcePolicyRevision = 2L,
            historyQuery = query,
        )
        val network = ProductPagedRows.networkActivity(
            rows = listOf(NetworkActivityRow(networkEntry(id = 9L))),
            next = null,
            cut = cut,
            sourceRevision = 5L,
        )

        assertEquals(2L, history.sourcePolicyRevision)
        assertEquals(query, history.historyQuery)
        assertNull(network.sourcePolicyRevision)
        assertNull(network.historyQuery)
    }

    @Test
    fun `paged rows reject a next token from another frozen cut`() {
        val cut = StableReadCut(maxRowId = 9L, capturedAtMillis = 1_000L)
        val otherCut = StableReadCut(maxRowId = 10L, capturedAtMillis = 1_000L)

        assertThrows(IllegalArgumentException::class.java) {
            ProductPagedRows.domainHistory(
                rows = listOf(domainRow(id = 9L)),
                next = PageToken(otherCut),
                cut = cut,
                sourceRevision = 4L,
                sourcePolicyRevision = 2L,
                historyQuery = DomainHistoryQuery(),
            )
        }
    }

    @Test
    fun `domain history read carries exact repository and policy provenance`() {
        val query = DomainHistoryQuery(decision = DomainDecision.ALLOWED, search = "safe")
        val cut = StableReadCut(maxRowId = 9L, capturedAtMillis = 1_000L)
        val history = Revisioned(
            revision = 12L,
            value = DomainHistoryRead(
                query = query,
                policyRevision = 5L,
                availability = DomainHistoryAvailability.RETAINED,
                result = ProductReadResult.Available(
                    StablePage(
                        rows = listOf(domainRow(id = 9L, decision = DomainDecision.ALLOWED)),
                        next = null,
                        cut = cut,
                    ),
                ),
            ),
        )

        assertEquals(query, history.value.query)
        assertEquals(12L, history.revision)
        assertEquals(5L, history.value.policyRevision)
        assertEquals(DomainHistoryAvailability.RETAINED, history.value.availability)
    }

    @Test
    fun `domain history availability rejects incoherent disabled and empty results`() {
        val query = DomainHistoryQuery()
        val emptyPage = StablePage<DomainHistoryRow>(
            rows = emptyList(),
            next = null,
            cut = StableReadCut(maxRowId = 0L, capturedAtMillis = 1_000L),
        )
        val retainedPage = StablePage(
            rows = listOf(domainRow(id = 9L)),
            next = null,
            cut = StableReadCut(maxRowId = 9L, capturedAtMillis = 1_000L),
        )
        val emptyPageWithNext = StablePage<DomainHistoryRow>(
            rows = emptyList(),
            next = PageToken(
                cut = StableReadCut(maxRowId = 0L, capturedAtMillis = 1_000L),
            ),
            cut = StableReadCut(maxRowId = 0L, capturedAtMillis = 1_000L),
        )

        assertEquals(
            DomainHistoryAvailability.DISABLED,
            DomainHistoryRead(
                query = query,
                policyRevision = 3L,
                availability = DomainHistoryAvailability.DISABLED,
                result = ProductReadResult.Disabled,
            ).availability,
        )
        assertEquals(
            DomainHistoryAvailability.EMPTY,
            DomainHistoryRead(
                query = query,
                policyRevision = 3L,
                availability = DomainHistoryAvailability.EMPTY,
                result = ProductReadResult.Available(emptyPage),
            ).availability,
        )
        assertThrows(IllegalArgumentException::class.java) {
            DomainHistoryRead(
                query = query,
                policyRevision = 3L,
                availability = DomainHistoryAvailability.DISABLED,
                result = ProductReadResult.Available(emptyPage),
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            DomainHistoryRead(
                query = query,
                policyRevision = 3L,
                availability = DomainHistoryAvailability.EMPTY,
                result = ProductReadResult.Available(retainedPage),
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            DomainHistoryRead(
                query = query,
                policyRevision = 3L,
                availability = DomainHistoryAvailability.EMPTY,
                result = ProductReadResult.Available(emptyPageWithNext),
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            DomainHistoryRead(
                query = query,
                policyRevision = 3L,
                availability = DomainHistoryAvailability.RETAINED,
                result = ProductReadResult.Disabled,
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            DomainHistoryRead(
                query = query,
                policyRevision = -1L,
                availability = DomainHistoryAvailability.EMPTY,
                result = ProductReadResult.Available(emptyPage),
            )
        }
    }

    @Test
    fun `retained domain availability permits an empty filtered result`() {
        val query = DomainHistoryQuery(search = "no-match")
        val history = DomainHistoryRead(
            query = query,
            policyRevision = 4L,
            availability = DomainHistoryAvailability.RETAINED,
            result = ProductReadResult.Available(
                StablePage(
                    rows = emptyList(),
                    next = null,
                    cut = StableReadCut(maxRowId = 8L, capturedAtMillis = 1_000L),
                ),
            ),
        )

        assertEquals(DomainHistoryAvailability.RETAINED, history.availability)
    }

    @Test
    fun `available Domain History rejects rows from another decision query`() {
        val query = DomainHistoryQuery(decision = DomainDecision.ALLOWED)

        assertThrows(IllegalArgumentException::class.java) {
            DomainHistoryRead(
                query = query,
                policyRevision = 4L,
                availability = DomainHistoryAvailability.RETAINED,
                result = ProductReadResult.Available(
                    StablePage(
                        rows = listOf(domainRow(id = 9L, decision = DomainDecision.BLOCKED)),
                        next = null,
                        cut = StableReadCut(maxRowId = 9L, capturedAtMillis = 1_000L),
                    ),
                ),
            )
        }
    }

    @Test
    fun `summary read requires its available payload to match the exact Top Domains vector`() {
        val query = ActivitySummaryQuery(
            dateLens = activityLens(),
            topDomainDecision = DomainDecision.ALLOWED,
            topDomainSearch = "safe",
        )
        val summary = summary(
            query = query,
            domainRevision = 8L,
            policyRevision = 5L,
            availability = DomainHistoryAvailability.RETAINED,
            topDomains = listOf(TopDomainRow("safe.example", 2L)),
        )
        val read = ActivitySummaryRead(
            query = query,
            topDomainsDomainRevision = 8L,
            topDomainsPolicyRevision = 5L,
            topDomainsAvailability = DomainHistoryAvailability.RETAINED,
            result = ProductReadResult.Available(summary),
        )

        assertEquals(8L, read.topDomainsDomainRevision)
        assertEquals(5L, read.topDomainsPolicyRevision)
        assertEquals(DomainHistoryAvailability.RETAINED, read.topDomainsAvailability)
        assertThrows(IllegalArgumentException::class.java) {
            read.copy(topDomainsDomainRevision = 9L)
        }
        assertThrows(IllegalArgumentException::class.java) {
            read.copy(topDomainsPolicyRevision = 6L)
        }
        assertThrows(IllegalArgumentException::class.java) {
            read.copy(topDomainsAvailability = DomainHistoryAvailability.EMPTY)
        }
        assertThrows(IllegalArgumentException::class.java) {
            read.copy(query = query.copy(topDomainSearch = "different"))
        }
    }

    @Test
    fun `summary Top Domains availability rejects retained rows when disabled or empty`() {
        val query = ActivitySummaryQuery(activityLens())
        val retained = listOf(TopDomainRow("blocked.example", 1L))

        assertThrows(IllegalArgumentException::class.java) {
            summary(
                query = query,
                domainRevision = 1L,
                policyRevision = 1L,
                availability = DomainHistoryAvailability.DISABLED,
                topDomains = retained,
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            summary(
                query = query,
                domainRevision = 1L,
                policyRevision = 1L,
                availability = DomainHistoryAvailability.EMPTY,
                topDomains = retained,
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            summary(
                query = query,
                domainRevision = -1L,
                policyRevision = 1L,
                availability = DomainHistoryAvailability.EMPTY,
                topDomains = emptyList(),
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            summary(
                query = query,
                domainRevision = 1L,
                policyRevision = -1L,
                availability = DomainHistoryAvailability.EMPTY,
                topDomains = emptyList(),
            )
        }
    }

    @Test
    fun `disabled summary read requires disabled Top Domains availability`() {
        val query = ActivitySummaryQuery(activityLens())

        assertEquals(
            DomainHistoryAvailability.DISABLED,
            ActivitySummaryRead(
                query = query,
                topDomainsDomainRevision = 3L,
                topDomainsPolicyRevision = 2L,
                topDomainsAvailability = DomainHistoryAvailability.DISABLED,
                result = ProductReadResult.Disabled,
            ).topDomainsAvailability,
        )
        assertThrows(IllegalArgumentException::class.java) {
            ActivitySummaryRead(
                query = query,
                topDomainsDomainRevision = 3L,
                topDomainsPolicyRevision = 2L,
                topDomainsAvailability = DomainHistoryAvailability.EMPTY,
                result = ProductReadResult.Disabled,
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            ActivitySummaryRead(
                query = query,
                topDomainsDomainRevision = -1L,
                topDomainsPolicyRevision = 2L,
                topDomainsAvailability = DomainHistoryAvailability.DISABLED,
                result = ProductReadResult.Disabled,
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            ActivitySummaryRead(
                query = query,
                topDomainsDomainRevision = 3L,
                topDomainsPolicyRevision = -1L,
                topDomainsAvailability = DomainHistoryAvailability.DISABLED,
                result = ProductReadResult.Disabled,
            )
        }
    }

    private fun domainRow(
        id: Long,
        decision: DomainDecision = DomainDecision.BLOCKED,
    ) = DomainHistoryRow(
        id = id,
        timestampMillis = 900L,
        domain = "example.com",
        decision = decision,
        reason = when (decision) {
            DomainDecision.ALLOWED -> FilterDecisionReason.DEFAULT_ALLOW
            DomainDecision.BLOCKED -> FilterDecisionReason.BLOCKLIST
        },
    )

    private fun networkEntry(
        id: Long,
        firstOccurrenceEpochMillis: Long = 900L,
        latestOccurrenceEpochMillis: Long = firstOccurrenceEpochMillis,
    ) = NetworkActivityEntry(
        id = id,
        latestOccurrenceEpochMillis = latestOccurrenceEpochMillis,
        firstOccurrenceEpochMillis = firstOccurrenceEpochMillis,
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
        occurrenceCount = 1,
    )

    private fun summary(
        query: ActivitySummaryQuery,
        domainRevision: Long,
        policyRevision: Long,
        availability: DomainHistoryAvailability,
        topDomains: List<TopDomainRow>,
    ) = ActivitySummary(
        digest = ActivityDigest(allowedCount = 1L, blockedCount = 2L, localProtectionUptimeMillis = 3L),
        topDomains = topDomains,
        dateLens = query.dateLens,
        capturedAtMillis = 5L,
        topDomainsDomainRevision = domainRevision,
        topDomainsPolicyRevision = policyRevision,
        topDomainsAvailability = availability,
        topDomainsDecision = query.topDomainDecision,
        topDomainsSearch = query.topDomainSearch,
    )

    private fun activityLens(): ActivityDateLens {
        val today = LocalDate.of(2026, 8, 1)
        val zone = ZoneId.of("UTC")
        val start = today.atStartOfDay(zone).toInstant().toEpochMilli()
        return ActivityDateLens(start, start, zone.id, today)
    }
}
