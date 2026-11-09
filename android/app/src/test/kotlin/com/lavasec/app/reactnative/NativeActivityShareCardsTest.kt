package com.lavasec.app.reactnative

import com.lavasec.app.platform.LoggingSettings
import com.lavasec.app.security.SecurityAvailability
import com.lavasec.app.security.SecurityOwnerState
import java.time.LocalDate
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.time.ZoneId
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class NativeActivityShareCardsTest {
    private val zone = ZoneId.of("Asia/Tokyo")
    private val today = LocalDate.of(2026, 10, 21)
    private val authority = NativeQueryReadAuthority(Any(), "window", 7L, true, false,
        SecurityOwnerState(availability = SecurityAvailability.Available), 0L)
    private val policy = LoggingSettings(keepCounts = true, keepHistory = true)
    private val summary = ActivityQueryResult(41, 9, "5m")
    private fun command(days: Long = 1) = ProductActivityCardQuery(today.minusDays(days - 1).atStartOfDay(zone).toEpochSecond(),
        today.plusDays(1).atStartOfDay(zone).toEpochSecond() - 1, false, "total", "month")
    private fun issue(owner: NativeActivityShareCards, days: Long = 1) = owner.issue(owner.begin(), authority, policy, "en", zone,
        command(days), summary, 29, today)!!

    @Test fun `native totals and range facts own copy instead of caller period`() {
        val owner = NativeActivityShareCards()
        assertEquals("today", issue(owner).content.getString("period"))
        assertEquals("week", issue(owner, 7).content.getString("period"))
        assertEquals("month", issue(owner, 21).content.getString("period"))
        val rolling = issue(owner, 30).content
        assertEquals("custom", rolling.getString("period"))
        assertEquals(41, rolling.getLong("allowed")); assertEquals(9, rolling.getLong("blocked"))
        assertFalse(rolling.has("buckets"))
        assertEquals("https://lavasecurity.app/", rolling.getString("payload"))
    }
    @Test fun `export cannot borrow changed host graph security clear policy locale or timezone`() {
        val owner = NativeActivityShareCards(); val grant = issue(owner)
        fun accept(a: NativeQueryReadAuthority = authority, policy: LoggingSettings? = this.policy, locale: String = "en", z: ZoneId = zone) =
            owner.accepted(grant.token, a, policy, locale, z)
        assertSame(grant, accept())
        listOf(authority.copy(host = Any()), authority.copy(nativeHostID = "replacement"),
            authority.copy(generation = 8), authority.copy(foreground = false), authority.copy(destroyed = true),
            authority.copy(security = null), authority.copy(security = authority.security!!.copy(authenticationRevision = 1)),
            authority.copy(displayClearRevision = 1)).forEach { assertNull(accept(it)) }
        assertNull(accept(policy = policy.copy(keepHistory = false))); assertNull(accept(locale = "ja")); assertNull(accept(z = ZoneId.of("UTC")))
    }
    @Test fun `unretained counts cannot be exported as measured zeroes even with domain history`() {
        val owner = NativeActivityShareCards()
        val zero = ActivityQueryResult(0, 0, "0m")
        for (unavailable in listOf(null, policy.copy(keepCounts = false))) {
            assertNull(owner.issue(owner.begin(), authority, unavailable, "en", zone, command(), zero, 29, today))
        }
        val measured = owner.issue(owner.begin(), authority, policy, "en", zone, command(), zero, 29, today)!!
        assertEquals(0, measured.content.getLong("allowed"))
        assertNull(owner.accepted(measured.token, authority, policy.copy(keepCounts = false), "en", zone))
    }

    @Test fun `retirement during suspended query prevents late issuance`() {
        val owner = NativeActivityShareCards(); val attempt = owner.begin(); owner.revoke()
        assertNull(owner.issue(attempt, authority, policy, "en", zone, command(), summary, 29, today))
    }
    @Test fun `old exact close cannot retire successor and a consumed token cannot export twice`() {
        val owner = NativeActivityShareCards(); val first = issue(owner); val second = issue(owner)
        owner.revoke(first.token)
        assertSame(second, owner.accepted(second.token, authority, policy, "en", zone))
        assertNull(owner.accepted(first.token, authority, policy, "en", zone))
        owner.revoke(second.token)
        assertNull(owner.accepted(second.token, authority, policy, "en", zone))
    }
    @Test fun `concurrent retirement waits for issuance and leaves no accepted grant`() {
        val issuing = CountDownLatch(1)
        val finishIssuing = CountDownLatch(1)
        val retiring = CountDownLatch(1)
        val retired = CountDownLatch(1)
        val executor = Executors.newFixedThreadPool(2)
        val owner = NativeActivityShareCards {
            issuing.countDown()
            check(finishIssuing.await(5, TimeUnit.SECONDS))
            "concurrent-card"
        }
        try {
            val attempt = owner.begin()
            val issuance = executor.submit<NativeActivityShareCards.Request?> {
                owner.issue(attempt, authority, policy, "en", zone, command(), summary, 29, today)
            }
            assertTrue(issuing.await(5, TimeUnit.SECONDS))
            val retirement = executor.submit {
                retiring.countDown()
                owner.revoke()
                retired.countDown()
            }
            assertTrue(retiring.await(5, TimeUnit.SECONDS))
            assertFalse("Retirement cannot finish while an earlier grant is still publishing",
                retired.await(200, TimeUnit.MILLISECONDS))
            finishIssuing.countDown()
            val grant = issuance.get(5, TimeUnit.SECONDS)!!
            retirement.get(5, TimeUnit.SECONDS)
            assertNull(owner.accepted(grant.token, authority, policy, "en", zone))
        } finally {
            finishIssuing.countDown()
            executor.shutdownNow()
        }
    }
    @Test fun `missing native authority never issues private projection`() {
        val owner = NativeActivityShareCards()
        assertNull(owner.issue(owner.begin(), authority.copy(security = null), policy, "en", zone, command(), summary, 29, today))
    }
    @Test fun `parser bounds ranges tokens chart vocabulary and ignores injected counts`() {
        fun parse(value: String) = org.json.JSONObject(value).let { ProductCommandParser.parse(it.getString("type"), JsonProductCommandInput(it)) }
        val valid = """{"type":"activity.card.query","start":100,"end":200,"hourly":false,"chart":"total","period":"today","allowed":9999}"""
        val parsed = (parse(valid) as ProductCommandParse.Supported).command as ProductActivityCardQuery
        assertEquals(100, parsed.start)
        for (invalid in listOf(valid.replace("\"end\":200", "\"end\":99"), valid.replace("\"end\":200", "\"end\":100000000"),
            valid.replace("\"total\"", "\"fake\""), valid.replace("\"today\"", "\"forever\""),
            """{"type":"activity.share","token":""}""")) assertFalse(parse(invalid) is ProductCommandParse.Supported)
    }
}
