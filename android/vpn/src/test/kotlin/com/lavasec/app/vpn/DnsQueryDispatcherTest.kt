package com.lavasec.app.vpn

import com.lavasec.app.foundation.dns.DnsDecision
import com.lavasec.app.foundation.dns.DnsMessageError
import com.lavasec.app.foundation.dns.DnsMessageException
import com.lavasec.app.foundation.filter.DomainRule
import com.lavasec.app.foundation.filter.DomainRuleSet
import com.lavasec.app.foundation.filter.FilterAction
import com.lavasec.app.foundation.filter.FilterDecision
import com.lavasec.app.foundation.filter.FilterDecisionReason
import com.lavasec.app.foundation.filter.FilterSnapshot
import com.lavasec.app.foundation.pause.PausePolicy
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class DnsQueryDispatcherTest {
    @Test
    fun `bootstrap wins over pause and filter`() {
        val bootstrap = byteArrayOf(1, 2, 3)

        val outcome = DnsQueryDispatcher.dispatch(
            bootstrapResponse = { bootstrap },
            pausedDecision = { pausedDecision() },
            filterDecision = { filterDecision() },
        )

        assertArrayEquals(bootstrap, (outcome as DnsQueryDispatchOutcome.Bootstrap).response)
    }

    @Test
    fun `bootstrap invokes neither pause nor filter`() {
        var pauseCalls = 0
        var filterCalls = 0

        DnsQueryDispatcher.dispatch(
            bootstrapResponse = { byteArrayOf(1) },
            pausedDecision = {
                pauseCalls++
                pausedDecision()
            },
            filterDecision = {
                filterCalls++
                filterDecision()
            },
        )

        assertEquals(0, pauseCalls)
        assertEquals(0, filterCalls)
    }

    @Test
    fun `pause wins over filter and invokes no normal filter closure`() {
        val paused = pausedDecision()
        var filterCalls = 0

        val outcome = DnsQueryDispatcher.dispatch(
            bootstrapResponse = { null },
            pausedDecision = { paused },
            filterDecision = {
                filterCalls++
                filterDecision()
            },
        )

        assertSame(paused, (outcome as DnsQueryDispatchOutcome.Paused).decision)
        assertEquals(0, filterCalls)
    }

    @Test
    fun `filter allow and block are returned after higher priority misses`() {
        val allow = DnsDecision.evaluate(
            dnsQuery("apple.com"),
            FilterSnapshot(blockRules = DomainRuleSet.EMPTY),
        )
        val block = DnsDecision.evaluate(
            dnsQuery("ads.example.com"),
            FilterSnapshot(blockRules = DomainRuleSet.of(DomainRule.parse("ads.example.com"))),
        )

        for (decision in listOf(allow, block)) {
            val outcome = DnsQueryDispatcher.dispatch(
                bootstrapResponse = { null },
                pausedDecision = { null },
                filterDecision = { decision },
            )

            assertSame(decision, (outcome as DnsQueryDispatchOutcome.Filter).decision)
        }
    }

    @Test
    fun `returned bootstrap bytes are defensive`() {
        val source = byteArrayOf(1, 2, 3)
        val outcome = DnsQueryDispatcher.dispatch(
            bootstrapResponse = { source },
            pausedDecision = { null },
            filterDecision = { filterDecision() },
        ) as DnsQueryDispatchOutcome.Bootstrap
        source[0] = 9
        val exposed = outcome.response
        exposed[1] = 9

        assertArrayEquals(byteArrayOf(1, 2, 3), outcome.response)
    }

    @Test
    fun `each closure is called at most once`() {
        var bootstrapCalls = 0
        var pauseCalls = 0
        var filterCalls = 0

        DnsQueryDispatcher.dispatch(
            bootstrapResponse = {
                bootstrapCalls++
                null
            },
            pausedDecision = {
                pauseCalls++
                null
            },
            filterDecision = {
                filterCalls++
                filterDecision()
            },
        )

        assertEquals(1, bootstrapCalls)
        assertEquals(1, pauseCalls)
        assertEquals(1, filterCalls)
    }

    private fun pausedDecision() = PausePolicy.queryDecision(
        pauseState = PausePolicy.start(
            nowMillis = 1_000,
            duration = com.lavasec.app.foundation.pause.PauseDuration.FIVE_MINUTES,
            protectionSessionId = 1,
        ),
        nowMillis = 1_000,
        activeProtectionSessionId = 1,
        wouldBeDecision = FilterDecision(FilterAction.BLOCK, FilterDecisionReason.BLOCKLIST),
    )!!

    private fun filterDecision(): DnsDecision =
        DnsDecision.Failure(DnsMessageException(DnsMessageError.MALFORMED_QUESTION), null)

    private fun dnsQuery(domain: String): ByteArray {
        val header = byteArrayOf(
            0x12, 0x34, 0x01, 0x00,
            0x00, 0x01, 0x00, 0x00,
            0x00, 0x00, 0x00, 0x00,
        )
        val question = domain.split('.').flatMap { label ->
            listOf(label.length.toByte()) + label.encodeToByteArray().toList()
        }.toByteArray() + byteArrayOf(0, 0, 1, 0, 1)
        return header + question
    }
}
