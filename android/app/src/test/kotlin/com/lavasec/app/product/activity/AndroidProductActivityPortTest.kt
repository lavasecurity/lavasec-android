package com.lavasec.app.product.activity

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.lavasec.app.data.ActivityDatabase
import com.lavasec.app.data.ActivityRepository
import com.lavasec.app.foundation.dns.DnsDecisionEvent
import com.lavasec.app.foundation.filter.FilterAction
import com.lavasec.app.foundation.filter.FilterDecision
import com.lavasec.app.foundation.filter.FilterDecisionReason
import com.lavasec.app.platform.LoggingSettings
import com.lavasec.app.product.Revisioned
import com.lavasec.app.product.StablePage
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.UUID
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class AndroidProductActivityPortTest {
    private lateinit var context: Context
    private lateinit var database: ActivityDatabase
    private lateinit var repository: ActivityRepository
    private lateinit var port: AndroidProductActivityPort

    private val now = Instant.parse("2026-07-19T12:00:00Z")

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        database = ActivityDatabase(context, "product-activity-${UUID.randomUUID()}.sqlite")
        repository = ActivityRepository(database, ZoneOffset.UTC, Clock.fixed(now, ZoneOffset.UTC))
        port = AndroidProductActivityPort(
            activityRepository = repository,
            networkActivityStore = EmptyNetworkActivityStore,
            ioDispatcher = kotlinx.coroutines.Dispatchers.Unconfined,
            clockEpochMillis = now::toEpochMilli,
        )
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun `summary publishes exact count domain policy and availability provenance`() = runTest {
        repository.record(event(1L, "allowed.example", FilterAction.ALLOW), LoggingSettings())
        repository.record(event(2L, "blocked.example", FilterAction.BLOCK), LoggingSettings())

        val evidence = port.loadSummary(
            query = ActivitySummaryQuery(lens()),
            topDomainsLimit = 20,
        )
        val read = evidence.value
        val summary = (read.result as ProductReadResult.Available).value

        assertEquals(repository.revisions().summaryCountRevision, evidence.revision)
        assertEquals(repository.revisions().domainLogRevision, read.topDomainsDomainRevision)
        assertEquals(repository.revisions().policyRevision, read.topDomainsPolicyRevision)
        assertEquals(DomainHistoryAvailability.RETAINED, read.topDomainsAvailability)
        assertEquals(ActivityDigest(1L, 1L, 0L), summary.digest)
        assertEquals(listOf(TopDomainRow("blocked.example", 1L)), summary.topDomains)
        assertEquals(evidence.revision, summary.summaryCountRevision)
    }

    @Test
    fun `disabled empty and retained Domain History remain distinct`() = runTest {
        repository.applyLoggingPolicy(
            LoggingSettings(keepCounts = true, keepHistory = false),
        )
        val disabled = port.loadDomainHistory(DomainHistoryPageRequest())
        assertEquals(ProductReadResult.Disabled, disabled.value.result)
        assertEquals(DomainHistoryAvailability.DISABLED, disabled.value.availability)
        assertEquals(1L, disabled.value.policyRevision)

        repository.applyLoggingPolicy(LoggingSettings())
        val empty = port.loadDomainHistory(DomainHistoryPageRequest())
        assertEquals(DomainHistoryAvailability.EMPTY, empty.value.availability)
        val emptyPage = (empty.value.result as ProductReadResult.Available).value
        assertTrue(emptyPage.rows.isEmpty())
        assertNull(emptyPage.next)

        repository.record(event(3L, "retained.example", FilterAction.BLOCK), LoggingSettings())
        val retained = port.loadDomainHistory(DomainHistoryPageRequest())
        assertEquals(DomainHistoryAvailability.RETAINED, retained.value.availability)
        assertEquals("retained.example", (retained.value.result as ProductReadResult.Available).value.rows.single().domain)
    }

    @Test
    fun `Domain History emits thirty rows and preserves frozen continuation cut`() = runTest {
        repeat(31) { index ->
            repository.record(
                event(
                    id = (index + 1).toLong(),
                    domain = "d${index.toString().padStart(2, '0')}.example",
                    action = FilterAction.BLOCK,
                    timestampMillis = now.toEpochMilli() - index,
                ),
                LoggingSettings(),
            )
        }

        val first = port.loadDomainHistory(DomainHistoryPageRequest())
        val firstPage = (first.value.result as ProductReadResult.Available).value
        assertEquals(30, firstPage.rows.size)
        val next = checkNotNull(firstPage.next)

        repository.record(
            event(100L, "new.example", FilterAction.BLOCK, now.toEpochMilli() + 1L),
            LoggingSettings(),
        )
        val second = port.loadDomainHistory(DomainHistoryPageRequest(token = next))
        val secondPage = (second.value.result as ProductReadResult.Available).value

        assertEquals(firstPage.cut, secondPage.cut)
        assertEquals(1, secondPage.rows.size)
        assertEquals(31L, secondPage.rows.single().id)
        assertNull(secondPage.next)
    }

    @Test
    fun `summary is disabled only when both count and history surfaces are disabled`() = runTest {
        repository.applyLoggingPolicy(
            LoggingSettings(keepCounts = false, keepHistory = false),
        )
        val disabled = port.loadSummary(ActivitySummaryQuery(lens()), 20)
        assertEquals(ProductReadResult.Disabled, disabled.value.result)
        assertEquals(DomainHistoryAvailability.DISABLED, disabled.value.topDomainsAvailability)

        repository.applyLoggingPolicy(
            LoggingSettings(keepCounts = true, keepHistory = false),
        )
        val countsOnly = port.loadSummary(ActivitySummaryQuery(lens()), 20)
        val summary = (countsOnly.value.result as ProductReadResult.Available).value
        assertTrue(summary.countsAndUptimeAvailable)
        assertEquals(DomainHistoryAvailability.DISABLED, summary.topDomainsAvailability)
        assertTrue(summary.topDomains.isEmpty())
    }

    private fun lens() = ActivityDateLens(
        startDayMillis = LocalDate.parse("2026-07-19").atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli(),
        endDayMillis = LocalDate.parse("2026-07-19").atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli(),
        zoneId = "UTC",
        today = LocalDate.parse("2026-07-19"),
    )

    private fun event(
        id: Long,
        domain: String,
        action: FilterAction,
        timestampMillis: Long = now.toEpochMilli(),
    ) = DnsDecisionEvent(
        id = id,
        timestampMillis = timestampMillis,
        normalizedDomain = domain,
        decision = FilterDecision(
            action = action,
            reason = when (action) {
                FilterAction.ALLOW -> FilterDecisionReason.DEFAULT_ALLOW
                FilterAction.BLOCK -> FilterDecisionReason.BLOCKLIST
            },
        ),
    )

    private object EmptyNetworkActivityStore : CredentialNetworkActivityStore {
        override fun revision(): Long = 0L

        override fun loadPage(request: NetworkActivityPageRequest): Revisioned<StablePage<NetworkActivityRow>> =
            Revisioned(0L, StablePage(emptyList(), null, com.lavasec.app.product.StableReadCut(0L, 0L)))

        override fun clear(): Long = 0L
    }
}
