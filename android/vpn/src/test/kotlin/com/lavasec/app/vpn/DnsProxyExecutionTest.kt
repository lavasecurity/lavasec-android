package com.lavasec.app.vpn

import com.lavasec.app.foundation.dns.DnsDecisionEvent
import com.lavasec.app.foundation.dns.DnsHealthEvent
import com.lavasec.app.foundation.dns.ProtectionUnavailableReason
import com.lavasec.app.foundation.dns.ResolverFailureReason
import com.lavasec.app.foundation.dns.ResolverKind
import com.lavasec.app.foundation.dns.ResolverTransport
import com.lavasec.app.foundation.filter.FilterAction
import com.lavasec.app.foundation.filter.FilterDecision
import com.lavasec.app.foundation.filter.FilterDecisionReason
import com.lavasec.app.foundation.health.PrimaryResolverOutcome
import com.lavasec.app.foundation.health.ProtectionFallback
import com.lavasec.app.foundation.health.ResolverFallbackCoverage
import com.lavasec.app.foundation.health.ResolverQueryHealthEvidence
import com.lavasec.app.foundation.pause.PauseState
import com.lavasec.app.foundation.pause.RuntimePauseDirective
import java.io.ByteArrayOutputStream
import java.util.Collections
import java.util.concurrent.CountDownLatch
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
@OptIn(ExperimentalCoroutinesApi::class)
class DnsProxyExecutionTest {
    @Test
    fun `only a well formed full NOERROR primary answer carries the organic answer fact`() = runTest {
        data class Case(val name: String, val reply: (ByteArray) -> ByteArray, val expected: Boolean)
        val cases = listOf(
            Case("answer", { testDnsAnswer(it) }, true),
            Case("NODATA", { q -> q.copyOf().also { it[2] = 0x81.toByte(); it[3] = 0x80.toByte() } }, false),
            Case("NXDOMAIN", { q -> q.copyOf().also { it[2] = 0x81.toByte(); it[3] = 0x83.toByte() } }, false),
            Case("REFUSED with answer", { q -> testDnsAnswer(q).also { it[3] = 0x85.toByte() } }, false),
            Case("malformed RR", { q -> testDnsAnswer(q).let { it.copyOf(it.size - 1) } }, false),
            Case("foreign transaction", { q -> testDnsAnswer(q).also { it[0] = (it[0].toInt() xor 1).toByte() } }, false),
            Case("extended BADVERS", { q ->
                (testDnsAnswer(q) + byteArrayOf(0, 0, 41, 4, 0, 1, 0, 0, 0, 0, 0)).also { it[11] = 1 }
            }, false),
        )
        for (case in cases) {
            val fixture = executionFixture()
            fixture.engine.resolverResult = ResolverResult.Answer(case.reply(fixture.plan.request.query),
                ResolverTransport.DEVICE_UDP, resolverQueryEvidence =
                    ResolverQueryHealthEvidence.primaryAccepted(fixture.plan.request.queryId, 1))
            assertEquals(case.name, case.expected, fixture.executeAndPublish().primaryAnswerBearing)
        }
        val failed = executionFixture()
        failed.engine.resolverResult = ResolverResult.Failure(ResolverFailureReason.TIMEOUT,
            resolver = ResolverKind.DEVICE, resolverQueryEvidence =
                ResolverQueryHealthEvidence.primaryFailed(failed.plan.request.queryId, ResolverFailureReason.TIMEOUT))
        assertFalse(failed.executeAndPublish().primaryAnswerBearing)
    }

    @Test
    fun `Device UDP Device TCP and Mullvad answers retain exact terminal metadata`() = runTest {
        data class Case(
            val name: String,
            val primary: ResolverKind,
            val transport: ResolverTransport,
            val expectedResolver: ResolverKind,
            val startNanos: Long,
            val endNanos: Long,
            val expectedLatencyMillis: Long,
        )
        val cases = listOf(
            Case(
                "Device UDP",
                ResolverKind.DEVICE,
                ResolverTransport.DEVICE_UDP,
                ResolverKind.DEVICE,
                2_000_000L,
                9_000_000L,
                7L,
            ),
            Case(
                "Device TCP",
                ResolverKind.DEVICE,
                ResolverTransport.DEVICE_TCP,
                ResolverKind.DEVICE,
                11_000_000L,
                16_000_000L,
                5L,
            ),
            Case(
                "Mullvad",
                ResolverKind.MULLVAD_DOH,
                ResolverTransport.MULLVAD_DOH,
                ResolverKind.MULLVAD_DOH,
                20_000_000L,
                24_000_000L,
                4L,
            ),
            Case(
                "backward clock",
                ResolverKind.DEVICE,
                ResolverTransport.DEVICE_UDP,
                ResolverKind.DEVICE,
                30_000_000L,
                10_000_000L,
                0L,
            ),
            Case(
                "overflowing backward clock",
                ResolverKind.DEVICE,
                ResolverTransport.DEVICE_UDP,
                ResolverKind.DEVICE,
                Long.MAX_VALUE,
                Long.MIN_VALUE + 2_000_000L,
                0L,
            ),
        )

        for (case in cases) {
            val fixture = executionFixture(primary = case.primary)
            val planned = fixture.plan.activity
            fixture.harness.monotonicClock.readings += case.startNanos
            fixture.harness.monotonicClock.readings += case.endNanos
            fixture.engine.resolverResult = ResolverResult.Answer(
                dnsMessage = testDnsAnswer(fixture.plan.request.query),
                transport = case.transport,
                resolverQueryEvidence = ResolverQueryHealthEvidence.primaryAccepted(
                    fixture.plan.request.queryId,
                    latencyMillis = 777L,
                ),
            )

            val publication = fixture.executeAndPublish()
            val activity = checkNotNull(publication.activity)

            assertDecisionIdentity(case.name, planned, activity)
            assertEquals(case.name, case.expectedResolver, activity.resolver)
            assertEquals(case.name, case.transport, activity.transport)
            assertEquals(case.name, case.expectedLatencyMillis, activity.latencyMillis)
            assertNull(case.name, activity.error)
            assertEquals(case.name, 1, fixture.engine.resolveCalls)
            // Planning captures the whole-query deadline before the execution start/end reads.
            assertEquals(case.name, 3, fixture.harness.monotonicClock.calls)
        }
    }

    @Test
    fun `coordinator health order and aggregate pass through without rewriting`() = runTest {
        val fixture = executionFixture()
        val first = DnsHealthEvent.ResolverAttemptFailed(
            ResolverKind.DEVICE,
            ResolverFailureReason.TIMEOUT,
        )
        val second = DnsHealthEvent.FallbackActivated(ResolverKind.MULLVAD_DOH)
        val evidence = ResolverQueryHealthEvidence.primaryAccepted(
            fixture.plan.request.queryId,
            latencyMillis = 913L,
        )
        fixture.harness.monotonicClock.readings += 1_000_000L
        fixture.harness.monotonicClock.readings += 6_000_000L
        fixture.engine.resolverResult = ResolverResult.Answer(
            dnsMessage = testDnsAnswer(fixture.plan.request.query),
            transport = ResolverTransport.DEVICE_UDP,
            healthEvents = listOf(first, second),
            resolverQueryEvidence = evidence,
        )

        val publication = fixture.executeAndPublish()

        assertEquals(listOf(first, second), publication.healthEvents)
        assertSame(evidence, publication.resolverQueryEvidence)
        assertEquals(913L, publication.resolverQueryEvidence?.latencyMillis)
        assertEquals(fixture.plan.request.queryId, publication.resolverQueryEvidence?.queryId)
        assertEquals(5L, publication.activity?.latencyMillis)
    }

    @Test
    fun `accepted fallback answers require exact plan coverage and compatible transport`() =
        runTest {
            val acceptedCases = listOf(
                PrimaryResolverOutcome.FAILED,
                PrimaryResolverOutcome.REJECTED,
            )
            for (primaryOutcome in acceptedCases) {
                val fixture = executionFixture(
                    primary = ResolverKind.DEVICE,
                    mullvadEnabled = true,
                    fallbackCoverage = ResolverFallbackCoverage.ENCRYPTED_DNS,
                )
                val evidence = when (primaryOutcome) {
                    PrimaryResolverOutcome.FAILED ->
                        ResolverQueryHealthEvidence.primaryFailed(
                            queryId = fixture.plan.request.queryId,
                            reason = ResolverFailureReason.TIMEOUT,
                            successfulFallback = ProtectionFallback.ENCRYPTED_DNS,
                        )

                    PrimaryResolverOutcome.REJECTED ->
                        ResolverQueryHealthEvidence.primaryRejectedWithFallback(
                            queryId = fixture.plan.request.queryId,
                            fallback = ProtectionFallback.ENCRYPTED_DNS,
                        )

                    PrimaryResolverOutcome.ACCEPTED -> error("not a fallback case")
                }
                fixture.engine.resolverResult = ResolverResult.Answer(
                    dnsMessage = testDnsAnswer(fixture.plan.request.query),
                    transport = ResolverTransport.MULLVAD_DOH,
                    resolverQueryEvidence = evidence,
                )

                val publication = fixture.executeAndPublish()

                assertEquals(ResolverKind.MULLVAD_DOH, publication.activity?.resolver)
                assertEquals(ResolverTransport.MULLVAD_DOH, publication.activity?.transport)
                assertSame(evidence, publication.resolverQueryEvidence)
                assertFalse(publication.primaryAnswerBearing)
                assertEquals(1, fixture.engine.resolveCalls)
            }
        }

    @Test
    fun `null foreign no-success and route-incoherent answer evidence fails closed`() = runTest {
        data class Case(
            val name: String,
            val primary: ResolverKind = ResolverKind.DEVICE,
            val mullvadEnabled: Boolean = false,
            val fallbackCoverage: ResolverFallbackCoverage = ResolverFallbackCoverage.NONE,
            val transport: ResolverTransport = ResolverTransport.DEVICE_UDP,
            val evidence: (Long) -> ResolverQueryHealthEvidence?,
        )
        val cases = listOf(
            Case("null") { null },
            Case("foreign query") { id ->
                ResolverQueryHealthEvidence.primaryAccepted(id + 1L, 1L)
            },
            Case("no terminal success") { id ->
                ResolverQueryHealthEvidence.primaryFailed(id, ResolverFailureReason.TIMEOUT)
            },
            Case(
                name = "Device primary accepted but Mullvad transported",
                transport = ResolverTransport.MULLVAD_DOH,
            ) { id -> ResolverQueryHealthEvidence.primaryAccepted(id, 1L) },
            Case(
                name = "Mullvad primary accepted but Device transported",
                primary = ResolverKind.MULLVAD_DOH,
                transport = ResolverTransport.DEVICE_TCP,
            ) { id -> ResolverQueryHealthEvidence.primaryAccepted(id, 1L) },
            Case(
                name = "fallback is absent from the plan",
                transport = ResolverTransport.MULLVAD_DOH,
            ) { id ->
                ResolverQueryHealthEvidence.primaryFailed(
                    id,
                    ResolverFailureReason.TIMEOUT,
                    successfulFallback = ProtectionFallback.ENCRYPTED_DNS,
                )
            },
        )

        for (case in cases) {
            val fixture = executionFixture(
                primary = case.primary,
                mullvadEnabled = case.mullvadEnabled,
                fallbackCoverage = case.fallbackCoverage,
            )
            fixture.engine.resolverResult = ResolverResult.Answer(
                dnsMessage = testDnsAnswer(fixture.plan.request.query),
                transport = case.transport,
                healthEvents = listOf(
                    DnsHealthEvent.ResolverRecovered(ResolverKind.DEVICE),
                ),
                resolverQueryEvidence = case.evidence(fixture.plan.request.queryId),
            )

            val publication = fixture.executeAndPublish()

            assertRuntimeUnavailable(case.name, publication)
            assertEquals(case.name, 1, fixture.engine.resolveCalls)
        }
    }

    @Test
    fun `real resolver failure emits bounded SERVFAIL exact terminal error and no synthetic health`() =
        runTest {
            val fixture = executionFixture()
            val evidence = ResolverQueryHealthEvidence.primaryFailed(
                fixture.plan.request.queryId,
                ResolverFailureReason.TIMEOUT,
            )
            fixture.harness.monotonicClock.readings += 1_000_000L
            fixture.harness.monotonicClock.readings += 4_000_000L
            fixture.engine.resolverResult = ResolverResult.Failure(
                reason = ResolverFailureReason.TIMEOUT,
                resolver = ResolverKind.DEVICE,
                resolverQueryEvidence = evidence,
            )

            val publication = fixture.executeAndPublish()
            val activity = checkNotNull(publication.activity)

            assertEquals(2, publication.dnsPayload().readUInt16(2) and 0x000f)
            assertEquals(ResolverKind.DEVICE, activity.resolver)
            assertNull(activity.transport)
            assertEquals(3L, activity.latencyMillis)
            assertEquals(ResolverFailureReason.TIMEOUT, activity.error)
            assertTrue(publication.healthEvents.isEmpty())
            assertSame(evidence, publication.resolverQueryEvidence)
            assertEquals(1, fixture.engine.resolveCalls)
        }

    @Test
    fun `fallback terminal reason need not equal aggregate primary reason`() = runTest {
        val fixture = executionFixture(
            mullvadEnabled = true,
            fallbackCoverage = ResolverFallbackCoverage.ENCRYPTED_DNS,
        )
        val primaryEvent = DnsHealthEvent.ResolverAttemptFailed(
            ResolverKind.DEVICE,
            ResolverFailureReason.TIMEOUT,
        )
        val fallbackEvent = DnsHealthEvent.ResolverAttemptFailed(
            ResolverKind.MULLVAD_DOH,
            ResolverFailureReason.TLS_FAILED,
        )
        val evidence = ResolverQueryHealthEvidence.primaryFailed(
            fixture.plan.request.queryId,
            ResolverFailureReason.TIMEOUT,
        )
        fixture.engine.resolverResult = ResolverResult.Failure(
            reason = ResolverFailureReason.TLS_FAILED,
            healthEvents = listOf(primaryEvent, fallbackEvent),
            resolver = ResolverKind.MULLVAD_DOH,
            resolverQueryEvidence = evidence,
        )

        val publication = fixture.executeAndPublish()

        assertEquals(ResolverFailureReason.TLS_FAILED, publication.activity?.error)
        assertEquals(ResolverKind.MULLVAD_DOH, publication.activity?.resolver)
        assertEquals(listOf(primaryEvent, fallbackEvent), publication.healthEvents)
        assertSame(evidence, publication.resolverQueryEvidence)
    }

    @Test
    fun `BACKED_OFF without attempt event remains health-event free`() = runTest {
        val fixture = executionFixture(primary = ResolverKind.MULLVAD_DOH)
        val evidence = ResolverQueryHealthEvidence.primaryFailed(
            fixture.plan.request.queryId,
            ResolverFailureReason.BACKED_OFF,
        )
        fixture.engine.resolverResult = ResolverResult.Failure(
            reason = ResolverFailureReason.BACKED_OFF,
            healthEvents = emptyList(),
            resolver = ResolverKind.MULLVAD_DOH,
            resolverQueryEvidence = evidence,
        )

        val publication = fixture.executeAndPublish()

        assertTrue(publication.healthEvents.isEmpty())
        assertEquals(ResolverFailureReason.BACKED_OFF, publication.activity?.error)
        assertSame(evidence, publication.resolverQueryEvidence)
    }

    @Test
    fun `failure evidence requires query terminal resolver no success and plausible route`() =
        runTest {
            data class Case(
                val name: String,
                val mullvadEnabled: Boolean = false,
                val fallbackCoverage: ResolverFallbackCoverage = ResolverFallbackCoverage.NONE,
                val resolver: ResolverKind?,
                val evidence: (Long) -> ResolverQueryHealthEvidence?,
            )
            val cases = listOf(
                Case("null evidence", resolver = ResolverKind.DEVICE) { null },
                Case("null terminal resolver", resolver = null) { id ->
                    ResolverQueryHealthEvidence.primaryFailed(id, ResolverFailureReason.TIMEOUT)
                },
                Case("foreign query", resolver = ResolverKind.DEVICE) { id ->
                    ResolverQueryHealthEvidence.primaryFailed(id + 1L, ResolverFailureReason.TIMEOUT)
                },
                Case("successful fallback", resolver = ResolverKind.DEVICE) { id ->
                    ResolverQueryHealthEvidence.primaryFailed(
                        id,
                        ResolverFailureReason.TIMEOUT,
                        successfulFallback = ProtectionFallback.ENCRYPTED_DNS,
                    )
                },
                Case("foreign terminal route", resolver = ResolverKind.MULLVAD_DOH) { id ->
                    ResolverQueryHealthEvidence.primaryFailed(id, ResolverFailureReason.TIMEOUT)
                },
            )

            for (case in cases) {
                val fixture = executionFixture(
                    mullvadEnabled = case.mullvadEnabled,
                    fallbackCoverage = case.fallbackCoverage,
                )
                fixture.engine.resolverResult = ResolverResult.Failure(
                    reason = ResolverFailureReason.TIMEOUT,
                    healthEvents = listOf(
                        DnsHealthEvent.ResolverAttemptFailed(
                            ResolverKind.DEVICE,
                            ResolverFailureReason.TIMEOUT,
                        ),
                    ),
                    resolver = case.resolver,
                    resolverQueryEvidence = case.evidence(fixture.plan.request.queryId),
                )

                val publication = fixture.executeAndPublish()

                assertRuntimeUnavailable(case.name, publication)
                assertEquals(case.name, 1, fixture.engine.resolveCalls)
            }
        }

    @Test
    fun `typed stale and cancelled suppress without completion clock policy or publication`() =
        runTest {
            for (reason in listOf(
                ResolverFailureReason.STALE_EPOCH,
                ResolverFailureReason.CANCELLED,
            )) {
                val fixture = executionFixture()
                val policyReadsAfterPlan = fixture.harness.policySource.calls
                val wallReadsAfterPlan = fixture.harness.wallClock.calls
                val clockReadsAfterPlan = fixture.harness.monotonicClock.calls
                fixture.engine.resolverResult = ResolverResult.Failure(
                    reason = reason,
                    healthEvents = listOf(
                        DnsHealthEvent.ResolverAttemptFailed(
                            ResolverKind.DEVICE,
                            ResolverFailureReason.TIMEOUT,
                        ),
                    ),
                    resolver = ResolverKind.DEVICE,
                    resolverQueryEvidence = ResolverQueryHealthEvidence.primaryFailed(
                        fixture.plan.request.queryId,
                        ResolverFailureReason.TIMEOUT,
                    ),
                )

                val result = fixture.harness.proxy.execute(fixture.plan)

                assertSame(reason.name, ProxyResult.NotPublishable, result)
                assertEquals(reason.name, clockReadsAfterPlan + 1, fixture.harness.monotonicClock.calls)
                assertEquals(
                    reason.name,
                    policyReadsAfterPlan,
                    fixture.harness.policySource.calls,
                )
                assertEquals(reason.name, wallReadsAfterPlan, fixture.harness.wallClock.calls)
                assertEquals(reason.name, 0, fixture.engine.fenceCalls)
                assertEquals(reason.name, 0, fixture.harness.sink.calls.get())
            }
        }

    @Test
    fun `actual cancellation from resolver and after resolver return propagates without publication`() =
        runTest {
            val thrown = executionFixture()
            val clockReadsAfterPlan = thrown.harness.monotonicClock.calls
            thrown.engine.resolveHandler = { throw CancellationException("resolver cancelled") }

            val direct = expectCancellation {
                thrown.harness.proxy.execute(thrown.plan)
            }

            assertEquals("resolver cancelled", direct.message)
            assertEquals(clockReadsAfterPlan + 1, thrown.harness.monotonicClock.calls)
            assertEquals(0, thrown.engine.fenceCalls)
            assertEquals(0, thrown.harness.sink.calls.get())

            val afterReturn = executionFixture()
            afterReturn.engine.resolverResult = acceptedAnswer(afterReturn.plan)
            supervisorScope {
                val task = async {
                    afterReturn.engine.resolveHandler = {
                        currentCoroutineContext().cancel(
                            CancellationException("cancelled after return"),
                        )
                        afterReturn.engine.resolverResult
                    }
                    afterReturn.harness.proxy.execute(afterReturn.plan)
                }
                val cancellation = expectCancellation { task.await() }
                assertEquals("cancelled after return", cancellation.message)
            }
            assertEquals(0, afterReturn.engine.fenceCalls)
            assertEquals(0, afterReturn.harness.sink.calls.get())
        }

    @Test
    fun `non-lifecycle resolver return reads end clock before observing cancellation`() = runTest {
        val fixture = executionFixture()
        val order = mutableListOf<String>()
        val clockReadsAfterPlan = fixture.harness.monotonicClock.calls
        fixture.engine.resolverResult = acceptedAnswer(fixture.plan)
        fixture.harness.monotonicClock.onRead = { call ->
            if (call == clockReadsAfterPlan + 2) order += "end-clock"
        }

        supervisorScope {
            val task = async {
                fixture.engine.resolveHandler = {
                    currentCoroutineContext().cancel(
                        CancellationException("cancelled after resolver return"),
                    )
                    order += "resolver-return"
                    fixture.engine.resolverResult
                }
                fixture.harness.proxy.execute(fixture.plan)
            }

            expectCancellation { task.await() }
            order += "cancellation-observed"
        }

        assertEquals(
            listOf("resolver-return", "end-clock", "cancellation-observed"),
            order,
        )
        assertEquals(clockReadsAfterPlan + 2, fixture.harness.monotonicClock.calls)
        assertEquals(0, fixture.engine.fenceCalls)
        assertEquals(0, fixture.harness.sink.calls.get())
    }

    @Test
    fun `foreign execution cannot consume or publish the owner plan`() = runTest {
        val owner = executionFixture()
        owner.engine.resolverResult = acceptedAnswer(owner.plan)
        val foreign = DnsProxyHarness()

        val overloadRejected = foreign.proxy.rejectOverloaded(owner.plan)
        val rejected = foreign.proxy.execute(owner.plan)
        val publication = owner.executeAndPublish()

        assertSame(ProxyResult.NotPublishable, overloadRejected)
        assertSame(ProxyResult.NotPublishable, rejected)
        assertEquals(0, foreign.monotonicClock.calls)
        assertEquals(0, foreign.policySource.calls)
        assertEquals(0, foreign.eventIds.calls)
        assertEquals(0, foreign.sink.calls.get())
        assertEquals(1, owner.engine.resolveCalls)
        assertEquals(0, publication.dnsPayload().readUInt16(2) and 0x000f)
    }

    @Test
    fun `sequential replay loser touches no dependency`() = runTest {
        val fixture = executionFixture()
        fixture.engine.resolverResult = acceptedAnswer(fixture.plan)

        val winner = fixture.harness.proxy.execute(fixture.plan)
        val counts = fixture.dependencyCounts()
        val loser = fixture.harness.proxy.execute(fixture.plan)

        assertTrue(winner is ProxyResult.Pending)
        assertSame(ProxyResult.NotPublishable, loser)
        assertEquals(counts, fixture.dependencyCounts())
        assertEquals(1, fixture.engine.resolveCalls)
        assertEquals(1, fixture.harness.eventIds.calls)
        assertEquals(fixture.plan.request.queryId, fixture.engine.resolvedRequests.single().queryId)
    }

    @Test
    fun `concurrent execution has one winner one resolve and dependency allocation`() {
        val fixture = executionFixture()
        fixture.engine.resolverResult = acceptedAnswer(fixture.plan)
        val start = CountDownLatch(1)
        val results = Collections.synchronizedList(mutableListOf<ProxyResult>())
        val callers = List(8) {
            Thread {
                runBlocking {
                    start.await()
                    results += fixture.harness.proxy.execute(fixture.plan)
                }
            }.apply { start() }
        }

        start.countDown()
        callers.forEach(Thread::join)

        assertEquals(1, results.count { it is ProxyResult.Pending })
        assertEquals(7, results.count { it === ProxyResult.NotPublishable })
        assertEquals(1, fixture.engine.resolveCalls)
        assertEquals(3, fixture.harness.monotonicClock.calls)
        assertEquals(2, fixture.harness.policySource.calls)
        assertEquals(1, fixture.harness.eventIds.calls)
        assertEquals(0, fixture.engine.fenceCalls)
        assertTrue((results.single { it is ProxyResult.Pending } as ProxyResult.Pending).publishIfCurrent())
        assertEquals(1, fixture.engine.fenceCalls)
        assertEquals(1, fixture.harness.sink.calls.get())
    }

    @Test
    fun `close before execution suppresses without dependencies and proxy never closes engine`() =
        runTest {
            val fixture = executionFixture()
            val policyReadsAfterPlan = fixture.harness.policySource.calls
            val wallReadsAfterPlan = fixture.harness.wallClock.calls
            val clockReadsAfterPlan = fixture.harness.monotonicClock.calls
            fixture.engine.close()

            val result = fixture.harness.proxy.execute(fixture.plan)

            assertSame(ProxyResult.NotPublishable, result)
            assertEquals(0, fixture.engine.resolveCalls)
            assertEquals(clockReadsAfterPlan, fixture.harness.monotonicClock.calls)
            assertEquals(policyReadsAfterPlan, fixture.harness.policySource.calls)
            assertEquals(wallReadsAfterPlan, fixture.harness.wallClock.calls)
            assertEquals(1, fixture.engine.closeCalls)
            assertEquals(0, fixture.engine.fenceCalls)
        }

    @Test
    fun `retirement between precheck and admission returns typed stale without resolver touch`() =
        runTest {
            val fixture = executionFixture()
            var underlyingResolverTouches = 0
            val clockReadsAfterPlan = fixture.harness.monotonicClock.calls
            fixture.harness.monotonicClock.onRead = { call ->
                if (call == clockReadsAfterPlan + 1) fixture.engine.close()
            }
            fixture.engine.resolveHandler = {
                if (!fixture.engine.current) {
                    ResolverResult.Failure(ResolverFailureReason.STALE_EPOCH)
                } else {
                    underlyingResolverTouches++
                    acceptedAnswer(fixture.plan)
                }
            }

            val result = fixture.harness.proxy.execute(fixture.plan)

            assertSame(ProxyResult.NotPublishable, result)
            assertEquals(1, fixture.engine.resolveCalls)
            assertEquals(0, underlyingResolverTouches)
            assertEquals(clockReadsAfterPlan + 1, fixture.harness.monotonicClock.calls)
            assertEquals(1, fixture.engine.closeCalls)
            assertEquals(0, fixture.engine.fenceCalls)
        }

    @Test
    fun `retirement during resolve suppresses before completion work`() = runTest {
        val fixture = executionFixture()
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        fixture.engine.resolveHandler = {
            entered.complete(Unit)
            release.await()
            acceptedAnswer(fixture.plan)
        }
        val policyReadsAfterPlan = fixture.harness.policySource.calls

        val task = async { fixture.harness.proxy.execute(fixture.plan) }
        runCurrent()
        entered.await()
        fixture.engine.close()
        release.complete(Unit)
        runCurrent()

        assertSame(ProxyResult.NotPublishable, task.await())
        assertEquals(policyReadsAfterPlan, fixture.harness.policySource.calls)
        assertEquals(0, fixture.engine.fenceCalls)
        assertEquals(0, fixture.harness.sink.calls.get())
    }

    @Test
    fun `retirement after resolver return and before pending creation suppresses`() = runTest {
        val fixture = executionFixture()
        fixture.engine.resolverResult = acceptedAnswer(fixture.plan)
        val clockReadsAfterPlan = fixture.harness.monotonicClock.calls
        fixture.harness.monotonicClock.onRead = { call ->
            if (call == clockReadsAfterPlan + 2) fixture.engine.close()
        }

        val result = fixture.harness.proxy.execute(fixture.plan)

        assertSame(ProxyResult.NotPublishable, result)
        assertEquals(1, fixture.engine.resolveCalls)
        assertEquals(clockReadsAfterPlan + 2, fixture.harness.monotonicClock.calls)
        assertEquals(0, fixture.engine.fenceCalls)
        assertEquals(0, fixture.harness.sink.calls.get())
    }

    @Test
    fun `retirement after pending creation suppresses inside atomic fence`() = runTest {
        val fixture = executionFixture()
        fixture.engine.resolverResult = acceptedAnswer(fixture.plan)

        val result = fixture.harness.proxy.execute(fixture.plan) as ProxyResult.Pending
        assertEquals(0, fixture.harness.sink.calls.get())
        fixture.engine.close()

        assertFalse(result.publishIfCurrent())
        assertEquals(1, fixture.engine.fenceCalls)
        assertEquals(0, fixture.harness.sink.calls.get())
    }

    @Test
    fun `sequential and concurrent publication enter engine fence and sink once`() = runTest {
        val sequential = executionFixture()
        sequential.engine.resolverResult = acceptedAnswer(sequential.plan)
        val sequentialPending =
            sequential.harness.proxy.execute(sequential.plan) as ProxyResult.Pending

        assertTrue(sequentialPending.publishIfCurrent())
        assertFalse(sequentialPending.publishIfCurrent())
        assertEquals(1, sequential.engine.fenceCalls)
        assertEquals(1, sequential.harness.sink.calls.get())

        val concurrent = executionFixture()
        concurrent.engine.resolverResult = acceptedAnswer(concurrent.plan)
        val concurrentPending =
            concurrent.harness.proxy.execute(concurrent.plan) as ProxyResult.Pending
        val start = CountDownLatch(1)
        val results = Collections.synchronizedList(mutableListOf<Boolean>())
        val callers = List(8) {
            Thread {
                start.await()
                results += concurrentPending.publishIfCurrent()
            }.apply { start() }
        }

        start.countDown()
        callers.forEach(Thread::join)

        assertEquals(1, results.count { it })
        assertEquals(7, results.count { !it })
        assertEquals(1, concurrent.engine.fenceCalls)
        assertEquals(1, concurrent.harness.sink.calls.get())
    }

    @Test
    fun `completion policy source failure and every identity mismatch suppress old output`() =
        runTest {
            val sourceFailure = executionFixture()
            sourceFailure.engine.resolverResult = acceptedAnswer(sourceFailure.plan)
            sourceFailure.harness.policySource.failure = IllegalStateException("source unavailable")

            assertSame(
                ProxyResult.NotPublishable,
                sourceFailure.harness.proxy.execute(sourceFailure.plan),
            )
            assertEquals(0, sourceFailure.engine.fenceCalls)
            assertEquals(0, sourceFailure.harness.sink.calls.get())

            val mismatches = listOf(
                testPolicyIdentity(configurationIdentity = testConfigurationIdentity(8L, "new")),
                testPolicyIdentity(artifactDigest = "new-digest"),
                testPolicyIdentity(revision = 12L),
                testPolicyIdentity(sourceIncarnation = 14L),
            )
            for (identity in mismatches) {
                val fixture = executionFixture()
                fixture.engine.resolverResult = acceptedAnswer(fixture.plan)
                fixture.harness.policySource.snapshot = ProxyPolicySnapshot(
                    identity = identity,
                    activeProtectionSessionId = TEST_PROTECTION_SESSION_ID,
                    pause = RuntimePauseDirective.Filtering,
                    decisions = fixture.harness.decisions,
                )

                val result = fixture.harness.proxy.execute(fixture.plan)

                assertSame(identity.toString(), ProxyResult.NotPublishable, result)
                assertEquals(identity.toString(), 0, fixture.engine.fenceCalls)
                assertEquals(identity.toString(), 0, fixture.harness.sink.calls.get())
            }
        }

    @Test
    fun `changed protection session suppresses otherwise identical completion snapshot`() =
        runTest {
            val fixture = executionFixture()
            fixture.engine.resolverResult = acceptedAnswer(fixture.plan)
            fixture.harness.policySource.snapshot = ProxyPolicySnapshot(
                identity = fixture.harness.policyIdentity,
                activeProtectionSessionId = TEST_PROTECTION_SESSION_ID + 1L,
                pause = RuntimePauseDirective.Filtering,
                decisions = fixture.harness.decisions,
            )

            val result = fixture.harness.proxy.execute(fixture.plan)

            assertSame(ProxyResult.NotPublishable, result)
            assertEquals(0, fixture.engine.fenceCalls)
            assertEquals(0, fixture.harness.sink.calls.get())
        }

    @Test
    fun `lifecycle and policy fences precede exception and evidence invariant mapping`() = runTest {
        val resolverException = executionFixture()
        resolverException.engine.resolveHandler = { throw IllegalStateException("resolver broke") }
        resolverException.harness.policySource.snapshot = ProxyPolicySnapshot(
            identity = testPolicyIdentity(sourceIncarnation = 99L),
            activeProtectionSessionId = TEST_PROTECTION_SESSION_ID,
            pause = RuntimePauseDirective.Filtering,
            decisions = resolverException.harness.decisions,
        )

        assertSame(
            ProxyResult.NotPublishable,
            resolverException.harness.proxy.execute(resolverException.plan),
        )
        assertEquals(0, resolverException.engine.fenceCalls)

        val invalidEvidence = executionFixture()
        invalidEvidence.engine.resolverResult = ResolverResult.Answer(
            testDnsAnswer(invalidEvidence.plan.request.query),
            ResolverTransport.DEVICE_UDP,
            resolverQueryEvidence = null,
        )
        invalidEvidence.harness.policySource.snapshot = ProxyPolicySnapshot(
            identity = testPolicyIdentity(revision = 99L),
            activeProtectionSessionId = TEST_PROTECTION_SESSION_ID,
            pause = RuntimePauseDirective.Filtering,
            decisions = invalidEvidence.harness.decisions,
        )

        assertSame(
            ProxyResult.NotPublishable,
            invalidEvidence.harness.proxy.execute(invalidEvidence.plan),
        )
        assertEquals(0, invalidEvidence.engine.fenceCalls)
    }

    @Test
    fun `foreign pause is inactive and initially paused plan rechecks same-artifact filter`() =
        runTest {
            val fixture = pausedFixture(
                decision = FilterDecision(FilterAction.BLOCK, FilterDecisionReason.BLOCKLIST),
            )
            fixture.engine.resolverResult = acceptedAnswer(fixture.plan)
            fixture.harness.decisions.result = FilterDecision.DEFAULT_ALLOW
            fixture.harness.policySource.snapshot = ProxyPolicySnapshot(
                identity = fixture.harness.policyIdentity,
                activeProtectionSessionId = TEST_PROTECTION_SESSION_ID,
                pause = RuntimePauseDirective.Paused(
                    PauseState(
                        deadlineMillis = TEST_NOW_MILLIS + 60_000L,
                        protectionSessionId = TEST_PROTECTION_SESSION_ID + 99L,
                    ),
                ),
                decisions = fixture.harness.decisions,
            )

            val publication = fixture.executeAndPublish()

            assertEquals(FilterDecisionReason.PAUSED_ALLOW, publication.activity?.decision?.reason)
            assertEquals(2, fixture.harness.decisions.decideCalls)
            assertEquals(2, fixture.harness.wallClock.calls)
        }

    @Test
    fun `expired pause current block replaces answer and clears Activity resolver metadata`() =
        runTest {
            val fixture = pausedFixture(
                decision = FilterDecision(FilterAction.BLOCK, FilterDecisionReason.BLOCKLIST),
            )
            val health = DnsHealthEvent.ResolverRecovered(ResolverKind.DEVICE)
            val evidence = ResolverQueryHealthEvidence.primaryAccepted(
                fixture.plan.request.queryId,
                1L,
            )
            fixture.engine.resolverResult = ResolverResult.Answer(
                dnsMessage = testDnsAnswer(fixture.plan.request.query, addressLastByte = 77),
                transport = ResolverTransport.DEVICE_UDP,
                healthEvents = listOf(health),
                resolverQueryEvidence = evidence,
            )
            fixture.harness.wallClock.now = TEST_NOW_MILLIS + 10L
            fixture.harness.policySource.snapshot = ProxyPolicySnapshot(
                identity = fixture.harness.policyIdentity,
                activeProtectionSessionId = TEST_PROTECTION_SESSION_ID,
                pause = RuntimePauseDirective.Paused(
                    PauseState(
                        deadlineMillis = TEST_NOW_MILLIS - 1L,
                        protectionSessionId = TEST_PROTECTION_SESSION_ID,
                    ),
                ),
                decisions = fixture.harness.decisions,
            )

            val publication = fixture.executeAndPublish()
            val activity = checkNotNull(publication.activity)

            assertEquals(FilterAction.BLOCK, activity.decision.action)
            assertEquals(FilterDecisionReason.BLOCKLIST, activity.decision.reason)
            assertNull(activity.resolver)
            assertNull(activity.transport)
            assertNull(activity.latencyMillis)
            assertNull(activity.error)
            assertEquals(listOf(health), publication.healthEvents)
            assertSame(evidence, publication.resolverQueryEvidence)
            assertFalse(publication.primaryAnswerBearing)
            assertArrayEquals(byteArrayOf(0, 0, 0, 0), publication.dnsPayload().takeLast(4).toByteArray())
        }

    @Test
    fun `expired pause current allow retains decision-time PAUSED_ALLOW`() = runTest {
        val fixture = pausedFixture(
            decision = FilterDecision(FilterAction.BLOCK, FilterDecisionReason.BLOCKLIST),
        )
        fixture.engine.resolverResult = acceptedAnswer(fixture.plan)
        fixture.harness.decisions.result = FilterDecision.DEFAULT_ALLOW
        fixture.harness.wallClock.now = TEST_NOW_MILLIS + 10L
        fixture.harness.policySource.snapshot = ProxyPolicySnapshot(
            identity = fixture.harness.policyIdentity,
            activeProtectionSessionId = TEST_PROTECTION_SESSION_ID,
            pause = RuntimePauseDirective.Paused(
                PauseState(
                    deadlineMillis = TEST_NOW_MILLIS - 1L,
                    protectionSessionId = TEST_PROTECTION_SESSION_ID,
                ),
            ),
            decisions = fixture.harness.decisions,
        )

        val publication = fixture.executeAndPublish()

        assertEquals(FilterDecisionReason.PAUSED_ALLOW, publication.activity?.decision?.reason)
        assertEquals(ResolverKind.DEVICE, publication.activity?.resolver)
        assertEquals(2, fixture.harness.decisions.decideCalls)
    }

    @Test
    fun `active paused would-be block uses captured cap on cacheable TTLs and preserves OPT`() =
        runTest {
            val fixture = pausedFixture(
                decision = FilterDecision(FilterAction.BLOCK, FilterDecisionReason.BLOCKLIST),
            )
            val upstream = testDnsResponseWithSectionTtls(fixture.plan.request.query)
            fixture.engine.resolverResult = ResolverResult.Answer(
                upstream,
                ResolverTransport.DEVICE_UDP,
                resolverQueryEvidence = ResolverQueryHealthEvidence.primaryAccepted(
                    fixture.plan.request.queryId,
                    1L,
                ),
            )

            val publication = fixture.executeAndPublish()
            val payload = publication.dnsPayload()
            val walk = checkNotNull(DnsResponseWireWalker.walk(payload))
            val ttls = walk.records.map { record -> payload.readUInt32(record.ttlOffset) }

            assertEquals(listOf(1L, 1L, 1L, 0x0102_0304L), ttls)
            assertEquals(1L, fixture.plan.plannedPauseMaximumResponseTtlSeconds)
            assertEquals(1, fixture.harness.decisions.decideCalls)
            assertEquals(2, fixture.harness.wallClock.calls)
        }

    @Test
    fun `active paused would-be allow preserves upstream TTLs`() = runTest {
        val fixture = pausedFixture(decision = FilterDecision.DEFAULT_ALLOW)
        val upstream = testDnsResponseWithSectionTtls(fixture.plan.request.query)
        fixture.engine.resolverResult = ResolverResult.Answer(
            upstream,
            ResolverTransport.DEVICE_TCP,
            resolverQueryEvidence = ResolverQueryHealthEvidence.primaryAccepted(
                fixture.plan.request.queryId,
                1L,
            ),
        )

        val publication = fixture.executeAndPublish()

        assertArrayEquals(upstream, publication.dnsPayload())
        assertNull(fixture.plan.plannedPauseMaximumResponseTtlSeconds)
        assertEquals(1, fixture.harness.decisions.decideCalls)
    }

    @Test
    fun `pause beginning during ordinary allow does not relabel re-filter or cap`() = runTest {
        val fixture = executionFixture()
        val upstream = testDnsResponseWithSectionTtls(fixture.plan.request.query)
        fixture.engine.resolverResult = ResolverResult.Answer(
            upstream,
            ResolverTransport.DEVICE_UDP,
            resolverQueryEvidence = ResolverQueryHealthEvidence.primaryAccepted(
                fixture.plan.request.queryId,
                1L,
            ),
        )
        fixture.harness.decisions.result =
            FilterDecision(FilterAction.BLOCK, FilterDecisionReason.BLOCKLIST)
        fixture.harness.policySource.snapshot = ProxyPolicySnapshot(
            identity = fixture.harness.policyIdentity,
            activeProtectionSessionId = TEST_PROTECTION_SESSION_ID,
            pause = RuntimePauseDirective.Paused(
                PauseState(
                    deadlineMillis = TEST_NOW_MILLIS + 60_000L,
                    protectionSessionId = TEST_PROTECTION_SESSION_ID,
                ),
            ),
            decisions = fixture.harness.decisions,
        )

        val publication = fixture.executeAndPublish()

        assertEquals(FilterDecisionReason.DEFAULT_ALLOW, publication.activity?.decision?.reason)
        assertArrayEquals(upstream, publication.dnsPayload())
        assertEquals(1, fixture.harness.decisions.decideCalls)
        assertEquals(1, fixture.harness.wallClock.calls)
    }

    @Test
    fun `completion filter exception and unavailable decision become health-only SERVFAIL`() =
        runTest {
            val cases = listOf(
                IllegalStateException("filter failed") to null,
                null to
                    FilterDecision(
                        FilterAction.BLOCK,
                        FilterDecisionReason.PROTECTION_UNAVAILABLE,
                    ),
            )
            for ((failure, decision) in cases) {
                val fixture = pausedFixture(
                    decision = FilterDecision(
                        FilterAction.BLOCK,
                        FilterDecisionReason.BLOCKLIST,
                    ),
                )
                fixture.engine.resolverResult = ResolverResult.Answer(
                    testDnsAnswer(fixture.plan.request.query),
                    ResolverTransport.DEVICE_UDP,
                    healthEvents = listOf(
                        DnsHealthEvent.ResolverRecovered(ResolverKind.DEVICE),
                    ),
                    resolverQueryEvidence = ResolverQueryHealthEvidence.primaryAccepted(
                        fixture.plan.request.queryId,
                        1L,
                    ),
                )
                fixture.harness.policySource.snapshot = ProxyPolicySnapshot(
                    identity = fixture.harness.policyIdentity,
                    activeProtectionSessionId = TEST_PROTECTION_SESSION_ID,
                    pause = RuntimePauseDirective.Filtering,
                    decisions = fixture.harness.decisions,
                )
                fixture.harness.decisions.failure = failure
                if (decision != null) fixture.harness.decisions.result = decision

                val publication = fixture.executeAndPublish()

                assertRuntimeUnavailable(failure?.message ?: "unavailable decision", publication)
            }
        }

    @Test
    fun `completion source and decision cancellation propagate while source errors suppress`() =
        runTest {
            val sourceError = executionFixture()
            sourceError.engine.resolverResult = acceptedAnswer(sourceError.plan)
            sourceError.harness.policySource.failure = IllegalStateException("source read failed")
            assertSame(
                ProxyResult.NotPublishable,
                sourceError.harness.proxy.execute(sourceError.plan),
            )

            val sourceCancellation = executionFixture()
            sourceCancellation.engine.resolverResult = acceptedAnswer(sourceCancellation.plan)
            sourceCancellation.harness.policySource.failure =
                CancellationException("source cancelled")
            val sourceThrown = expectCancellation {
                sourceCancellation.harness.proxy.execute(sourceCancellation.plan)
            }
            assertEquals("source cancelled", sourceThrown.message)
            assertEquals(0, sourceCancellation.engine.fenceCalls)

            val decisionCancellation = pausedFixture(
                decision = FilterDecision(
                    FilterAction.BLOCK,
                    FilterDecisionReason.BLOCKLIST,
                ),
            )
            decisionCancellation.engine.resolverResult = acceptedAnswer(decisionCancellation.plan)
            decisionCancellation.harness.policySource.snapshot = ProxyPolicySnapshot(
                identity = decisionCancellation.harness.policyIdentity,
                activeProtectionSessionId = TEST_PROTECTION_SESSION_ID,
                pause = RuntimePauseDirective.Filtering,
                decisions = decisionCancellation.harness.decisions,
            )
            decisionCancellation.harness.decisions.failure =
                CancellationException("decision cancelled")
            val decisionThrown = expectCancellation {
                decisionCancellation.harness.proxy.execute(decisionCancellation.plan)
            }
            assertEquals("decision cancelled", decisionThrown.message)
            assertEquals(0, decisionCancellation.engine.fenceCalls)
        }

    @Test
    fun `TTL cap failure suppresses Activity and coordinator evidence`() = runTest {
        val fixture = pausedFixture(
            decision = FilterDecision(FilterAction.BLOCK, FilterDecisionReason.BLOCKLIST),
        )
        fixture.engine.resolverResult = ResolverResult.Answer(
            dnsMessage = byteArrayOf(1, 2, 3),
            transport = ResolverTransport.DEVICE_UDP,
            healthEvents = listOf(
                DnsHealthEvent.ResolverRecovered(ResolverKind.DEVICE),
            ),
            resolverQueryEvidence = ResolverQueryHealthEvidence.primaryAccepted(
                fixture.plan.request.queryId,
                1L,
            ),
        )

        val publication = fixture.executeAndPublish()

        assertRuntimeUnavailable("TTL cap failure", publication)
        assertEquals(1, fixture.engine.resolveCalls)
    }

    @Test
    fun `valid selected primary and fallback answers are capped only at client publication`() = runTest {
        for (fallback in listOf(false, true)) for (size in listOf(1_252, 1_253, 4_096)) {
            val fixture = executionFixture(mullvadEnabled = fallback,
                fallbackCoverage = if (fallback) ResolverFallbackCoverage.ENCRYPTED_DNS else ResolverFallbackCoverage.NONE)
            val query = fixture.plan.request.query
            val source = paddedDnsAnswer(query, size)
            val evidence = if (fallback) ResolverQueryHealthEvidence.primaryFailed(fixture.plan.request.queryId,
                ResolverFailureReason.TIMEOUT, ProtectionFallback.ENCRYPTED_DNS)
                else ResolverQueryHealthEvidence.primaryAccepted(fixture.plan.request.queryId, 1L)
            val transport = if (fallback) ResolverTransport.MULLVAD_DOH else ResolverTransport.DEVICE_TCP
            fixture.engine.resolverResult = ResolverResult.Answer(source, transport, resolverQueryEvidence = evidence)

            val publication = fixture.executeAndPublish()
            val delivered = publication.dnsPayload()
            assertEquals(size.toString(), 1, fixture.engine.resolveCalls)
            assertSame(evidence, publication.resolverQueryEvidence)
            assertEquals(transport, publication.activity!!.transport)
            assertNull(publication.activity.error)
            assertTrue(publication.healthEvents.isEmpty())
            if (size == DnsOnlyTunContract.MAX_RESPONSE_DNS_BYTES) {
                assertArrayEquals(source, delivered)
            } else {
                assertEquals(12 + fixture.plan.request.question.questionBytes(query).size, delivered.size)
                assertEquals(0x0200, delivered.readUInt16(2) and 0x0200)
                assertEquals(0, delivered.readUInt16(6))
                assertEquals(0, delivered.readUInt16(10))
                assertFalse(publication.primaryAnswerBearing)
            }
            assertEquals(0, source.readUInt16(2) and 0x0200)
        }
    }

    @Test
    fun `malformed oversized engine answer fails closed without re-resolution`() = runTest {
        val fixture = executionFixture()
        fixture.engine.resolverResult = ResolverResult.Answer(
            testDnsAnswer(fixture.plan.request.query).copyOf(1_253), ResolverTransport.DEVICE_UDP,
            resolverQueryEvidence = ResolverQueryHealthEvidence.primaryAccepted(fixture.plan.request.queryId, 1L))
        assertRuntimeUnavailable("trailing bytes are not a valid selected answer", fixture.executeAndPublish())
        assertEquals(1, fixture.engine.resolveCalls)
    }


    @Test
    fun `unexpected resolver exception becomes one health-only runtime-unavailable result`() =
        runTest {
            val fixture = executionFixture()
            fixture.engine.resolveHandler = { throw IllegalStateException("unexpected resolver") }

            val publication = fixture.executeAndPublish()

            assertRuntimeUnavailable("unexpected resolver exception", publication)
            assertEquals(1, fixture.engine.resolveCalls)
        }

    @Test
    fun `publication snapshots resolver bytes and health before the atomic fence`() = runTest {
        val fixture = executionFixture()
        val response = testDnsAnswer(fixture.plan.request.query, addressLastByte = 19)
        val event = DnsHealthEvent.ResolverRecovered(ResolverKind.DEVICE)
        val health = mutableListOf<DnsHealthEvent>(event)
        fixture.engine.resolverResult = ResolverResult.Answer(
            response,
            ResolverTransport.DEVICE_UDP,
            healthEvents = health,
            resolverQueryEvidence = ResolverQueryHealthEvidence.primaryAccepted(
                fixture.plan.request.queryId,
                1L,
            ),
        )

        val pending = fixture.harness.proxy.execute(fixture.plan) as ProxyResult.Pending
        response.fill(0)
        health.clear()
        assertEquals(0, fixture.harness.sink.calls.get())
        assertTrue(pending.publishIfCurrent())
        val publication = fixture.harness.sink.publications.single()
        val exposed = publication.responsePacket
        exposed.fill(0)

        assertEquals(19, publication.dnsPayload().last().toInt() and 0xff)
        assertEquals(listOf(event), publication.healthEvents)
        assertThrows(UnsupportedOperationException::class.java) {
            @Suppress("UNCHECKED_CAST")
            (publication.healthEvents as MutableList<DnsHealthEvent>).add(event)
        }
        assertEquals(1, fixture.engine.resolveCalls)
        assertEquals(1, fixture.engine.fenceCalls)
    }

    private data class ExecutionFixture(
        val harness: DnsProxyHarness,
        val engine: TestReadyDnsEngine,
        val plan: ProxyPlan.Forward,
    ) {
        suspend fun executeAndPublish(): ProxyPublication {
            val before = harness.sink.publications.size
            val result = harness.proxy.execute(plan)
            assertTrue("expected pending result but was $result", result is ProxyResult.Pending)
            assertEquals(0, harness.sink.publications.size - before)
            assertTrue((result as ProxyResult.Pending).publishIfCurrent())
            return harness.sink.publications[before]
        }

        fun dependencyCounts(): List<Int> = listOf(
            engine.resolveCalls,
            engine.currentChecks,
            engine.fenceCalls,
            harness.policySource.calls,
            harness.eventIds.calls,
            harness.wallClock.calls,
            harness.monotonicClock.calls,
            harness.sink.calls.get(),
        )
    }

    private fun executionFixture(
        primary: ResolverKind = ResolverKind.DEVICE,
        mullvadEnabled: Boolean = primary == ResolverKind.MULLVAD_DOH,
        fallbackCoverage: ResolverFallbackCoverage = ResolverFallbackCoverage.NONE,
        harness: DnsProxyHarness = DnsProxyHarness(),
    ): ExecutionFixture {
        val context = testNetworkContext()
        val plan = testResolverPlan(
            networkContext = context,
            mullvadEnabled = mullvadEnabled,
            primaryResolverKind = primary,
            fallbackCoverage = fallbackCoverage,
        )
        val engine = harness.engine(
            networkContext = context,
            resolverPlan = plan,
            epoch = testEpoch(harness.policyIdentity, plan),
        )
        val forward = harness.proxy.plan(testDnsPacket(), engine) as ProxyPlan.Forward
        return ExecutionFixture(harness, engine, forward)
    }

    private fun pausedFixture(
        decision: FilterDecision,
    ): ExecutionFixture {
        val harness = DnsProxyHarness(
            decision = decision,
            pause = RuntimePauseDirective.Paused(
                PauseState(
                    deadlineMillis = TEST_NOW_MILLIS + 60_000L,
                    protectionSessionId = TEST_PROTECTION_SESSION_ID,
                ),
            ),
        )
        return executionFixture(harness = harness)
    }

    private fun acceptedAnswer(plan: ProxyPlan.Forward): ResolverResult.Answer =
        ResolverResult.Answer(
            dnsMessage = testDnsAnswer(plan.request.query),
            transport = ResolverTransport.DEVICE_UDP,
            resolverQueryEvidence = ResolverQueryHealthEvidence.primaryAccepted(
                plan.request.queryId,
                1L,
            ),
        )

    private fun assertDecisionIdentity(
        message: String,
        planned: DnsDecisionEvent,
        terminal: DnsDecisionEvent,
    ) {
        assertEquals(message, planned.id, terminal.id)
        assertEquals(message, planned.timestampMillis, terminal.timestampMillis)
        assertEquals(message, planned.normalizedDomain, terminal.normalizedDomain)
        assertEquals(message, planned.decision, terminal.decision)
    }

    private fun assertRuntimeUnavailable(
        message: String,
        publication: ProxyPublication,
    ) {
        assertEquals(message, 2, publication.dnsPayload().readUInt16(2) and 0x000f)
        assertNull(message, publication.activity)
        assertEquals(
            message,
            listOf(
                DnsHealthEvent.ProtectionUnavailable(
                    ProtectionUnavailableReason.RuntimeUnavailable,
                ),
            ),
            publication.healthEvents,
        )
        assertNull(message, publication.resolverQueryEvidence)
    }

    private suspend fun expectCancellation(
        block: suspend () -> Unit,
    ): CancellationException {
        try {
            block()
        } catch (cancelled: CancellationException) {
            return cancelled
        }
        throw AssertionError("expected CancellationException")
    }

    private fun paddedDnsAnswer(query: ByteArray, size: Int): ByteArray {
        val answer = testDnsAnswer(query).also { it.writeUInt16ForExecutionTest(10, 1) }
        val padding = size - answer.size - 15
        require(padding >= 0)
        val option = byteArrayOf(0, 12, (padding ushr 8).toByte(), padding.toByte()) + ByteArray(padding)
        return answer + testOptRecord(option)
    }

    private fun testDnsAnswer(
        query: ByteArray,
        ttl: Long = 300L,
        addressLastByte: Int = 53,
    ): ByteArray = ByteArrayOutputStream().apply {
        val headerAndQuestion = query.copyOf()
        headerAndQuestion.writeUInt16ForExecutionTest(2, 0x8180)
        headerAndQuestion.writeUInt16ForExecutionTest(6, 1)
        write(headerAndQuestion)
        write(byteArrayOf(0xc0.toByte(), 0x0c))
        appendUInt16ForExecutionTest(1)
        appendUInt16ForExecutionTest(1)
        appendUInt32ForExecutionTest(ttl)
        appendUInt16ForExecutionTest(4)
        write(byteArrayOf(192.toByte(), 0, 2, addressLastByte.toByte()))
    }.toByteArray()

    private fun testDnsResponseWithSectionTtls(query: ByteArray): ByteArray =
        ByteArrayOutputStream().apply {
            val headerAndQuestion = query.copyOf()
            headerAndQuestion.writeUInt16ForExecutionTest(2, 0x8180)
            headerAndQuestion.writeUInt16ForExecutionTest(6, 1)
            headerAndQuestion.writeUInt16ForExecutionTest(8, 1)
            headerAndQuestion.writeUInt16ForExecutionTest(10, 2)
            write(headerAndQuestion)
            appendAddressRecordForExecutionTest(600L, 1)
            appendAddressRecordForExecutionTest(700L, 2)
            appendAddressRecordForExecutionTest(800L, 3)
            write(0)
            appendUInt16ForExecutionTest(41)
            appendUInt16ForExecutionTest(1_232)
            appendUInt32ForExecutionTest(0x0102_0304L)
            appendUInt16ForExecutionTest(0)
        }.toByteArray()

    private fun ByteArrayOutputStream.appendAddressRecordForExecutionTest(
        ttl: Long,
        addressLastByte: Int,
    ) {
        write(byteArrayOf(0xc0.toByte(), 0x0c))
        appendUInt16ForExecutionTest(1)
        appendUInt16ForExecutionTest(1)
        appendUInt32ForExecutionTest(ttl)
        appendUInt16ForExecutionTest(4)
        write(byteArrayOf(192.toByte(), 0, 2, addressLastByte.toByte()))
    }

    private fun ByteArrayOutputStream.appendUInt16ForExecutionTest(value: Int) {
        write((value ushr 8) and 0xff)
        write(value and 0xff)
    }

    private fun ByteArrayOutputStream.appendUInt32ForExecutionTest(value: Long) {
        write((value ushr 24).toInt() and 0xff)
        write((value ushr 16).toInt() and 0xff)
        write((value ushr 8).toInt() and 0xff)
        write(value.toInt() and 0xff)
    }

    private fun ByteArray.writeUInt16ForExecutionTest(offset: Int, value: Int) {
        this[offset] = (value ushr 8).toByte()
        this[offset + 1] = value.toByte()
    }
}
