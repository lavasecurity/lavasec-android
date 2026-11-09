package com.lavasec.app.vpn

import com.lavasec.app.foundation.dns.ProtectionUnavailableReason
import com.lavasec.app.foundation.dns.ResolverFailureReason
import com.lavasec.app.foundation.dns.ResolverKind
import com.lavasec.app.foundation.dns.ResolverTransport
import com.lavasec.app.foundation.filter.FilterAction
import com.lavasec.app.foundation.filter.FilterDecision
import com.lavasec.app.foundation.filter.FilterDecisionReason
import com.lavasec.app.foundation.health.ResolverQueryHealthEvidence
import java.util.Collections
import java.util.concurrent.CountDownLatch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class DnsProxyRuntimeCapabilityTest {
    @Test
    fun `every non drop planning shape owns one bounded replacement SERVFAIL`() {
        val malformedHarness = DnsProxyHarness()
        val malformedQuery = ByteArray(12).also {
            it[4] = 0
            it[5] = 1
        }
        val malformed = malformedHarness.proxy.plan(
            testDnsPacket(malformedQuery),
            malformedHarness.engine(),
        )

        val bootstrapHarness = DnsProxyHarness()
        val bootstrapContext = testNetworkContext()
        val bootstrapResolverPlan = testResolverPlan(
            networkContext = bootstrapContext,
            mullvadEnabled = true,
            primaryResolverKind = ResolverKind.MULLVAD_DOH,
        )
        val bootstrap = bootstrapHarness.proxy.plan(
            testDnsPacket(testDnsQuery("dns.mullvad.net")),
            bootstrapHarness.engine(
                networkContext = bootstrapContext,
                resolverPlan = bootstrapResolverPlan,
                epoch = testEpoch(bootstrapHarness.policyIdentity, bootstrapResolverPlan),
            ),
        )

        val blockedHarness = DnsProxyHarness(
            decision = FilterDecision(FilterAction.BLOCK, FilterDecisionReason.BLOCKLIST),
        )
        val blocked = blockedHarness.proxy.plan(
            testDnsPacket(),
            blockedHarness.engine(),
        )

        val forwardHarness = DnsProxyHarness()
        val forward = forwardHarness.proxy.plan(
            testDnsPacket(),
            forwardHarness.engine(),
        )

        val unavailableHarness = DnsProxyHarness()
        val unavailable = unavailableHarness.proxy.planUnavailable(
            testDnsPacket(),
            ProtectionUnavailableReason.RuntimeUnavailable,
        )

        listOf(malformed, bootstrap, blocked, forward, unavailable).forEach { plan ->
            assertFalse(plan is ProxyPlan.Drop)
            assertBoundedServfail(plan.replacementSnapshot())
        }
    }

    @Test
    fun `replacement snapshots retain defensive bytes at the tunnel packet ceiling`() {
        val query = testDnsQuery()
        val padding = ByteArray(DnsOnlyTunContract.MTU - 28 - query.size - 11)
        val packet = testDnsPacket(
            testDnsQuery(
                additionalRecords = testOptRecord(padding),
                additionalCount = 1,
            ),
        )
        val harness = DnsProxyHarness()
        val plan = harness.proxy.plan(packet, harness.engine()) as ProxyPlan.Forward
        val expected = plan.replacement.snapshot()

        assertEquals(DnsOnlyTunContract.MTU, packet.size)
        packet.fill(0)
        val exposed = plan.replacement.snapshot()
        exposed.fill(0)

        assertArrayEquals(expected, plan.replacement.snapshot())
        assertBoundedServfail(expected)
    }

    @Test
    fun `overload rejection consumes forward and publishes only overload activity`() = runTest {
        val sink = RecordingPublicationSink()
        val harness = DnsProxyHarness(publicationSink = sink)
        val engine = harness.engine()
        val plan = harness.proxy.plan(testDnsPacket(), engine) as ProxyPlan.Forward
        val replacement = plan.replacement.snapshot()

        val overloaded = harness.proxy.rejectOverloaded(plan) as ProxyResult.Pending

        assertSame(ProxyResult.NotPublishable, harness.proxy.execute(plan))
        assertTrue(overloaded.publishIfCurrent())
        val publication = sink.publications.single()
        assertArrayEquals(replacement, publication.responsePacket)
        assertEquals(ResolverFailureReason.OVERLOADED, publication.activity?.error)
        assertEquals(engine.resolverPlan.primaryResolverKind, publication.activity?.resolver)
        assertNull(publication.activity?.transport)
        assertNull(publication.activity?.latencyMillis)
        assertTrue(publication.healthEvents.isEmpty())
        assertNull(publication.resolverQueryEvidence)
        assertEquals(0, engine.resolveCalls)
        assertEquals(0, harness.sink.calls.get())
    }

    @Test
    fun `foreign overload rejection cannot consume the owner forward`() = runTest {
        val owner = DnsProxyHarness()
        val engine = owner.engine()
        val plan = owner.proxy.plan(testDnsPacket(), engine) as ProxyPlan.Forward
        engine.resolverResult = acceptedAnswer(plan)
        val foreign = DnsProxyHarness()

        assertSame(ProxyResult.NotPublishable, foreign.proxy.rejectOverloaded(plan))
        val accepted = owner.proxy.execute(plan) as ProxyResult.Pending
        assertTrue(accepted.publishIfCurrent())

        assertEquals(1, engine.resolveCalls)
        assertEquals(0, foreign.sink.calls.get())
        assertNull(owner.sink.publications.single().activity?.error)
    }

    @Test
    fun `concurrent execute and overload rejection have exactly one capability winner`() {
        val harness = DnsProxyHarness()
        val engine = harness.engine()
        val plan = harness.proxy.plan(testDnsPacket(), engine) as ProxyPlan.Forward
        engine.resolverResult = acceptedAnswer(plan)
        val start = CountDownLatch(1)
        val results = Collections.synchronizedList(mutableListOf<ProxyResult>())
        val execute = Thread {
            start.await()
            results += runBlocking { harness.proxy.execute(plan) }
        }.apply { start() }
        val overload = Thread {
            start.await()
            results += harness.proxy.rejectOverloaded(plan)
        }.apply { start() }

        start.countDown()
        execute.join()
        overload.join()

        assertEquals(1, results.count { it is ProxyResult.Pending })
        assertEquals(1, results.count { it === ProxyResult.NotPublishable })
        val winner = results.single { it is ProxyResult.Pending } as ProxyResult.Pending
        assertTrue(winner.publishIfCurrent())
        val publication = harness.sink.publications.single()
        if (publication.activity?.error == ResolverFailureReason.OVERLOADED) {
            assertEquals(0, engine.resolveCalls)
        } else {
            assertNull(publication.activity?.error)
            assertEquals(1, engine.resolveCalls)
        }
    }

    private fun ProxyPlan.replacementSnapshot(): ByteArray = when (this) {
        is ProxyPlan.Immediate -> replacement.snapshot()
        is ProxyPlan.Forward -> replacement.snapshot()
        ProxyPlan.Drop -> error("drop has no replacement")
    }

    private fun assertBoundedServfail(packet: ByteArray) {
        assertTrue(packet.size <= DnsOnlyTunContract.MTU)
        assertEquals(2, packet.copyOfRange(28, packet.size).readUInt16(2) and 0x000f)
    }

    private fun acceptedAnswer(plan: ProxyPlan.Forward): ResolverResult.Answer =
        ResolverResult.Answer(
            dnsMessage = plan.request.query.copyOf().also { response ->
                response[2] = 0x81.toByte()
                response[3] = 0x80.toByte()
            },
            transport = ResolverTransport.DEVICE_UDP,
            resolverQueryEvidence = ResolverQueryHealthEvidence.primaryAccepted(
                plan.request.queryId,
                1L,
            ),
        )
}
