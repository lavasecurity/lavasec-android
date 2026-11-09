package com.lavasec.app.vpn

import com.lavasec.app.foundation.dns.DnsHealthEvent
import com.lavasec.app.foundation.dns.DnsMessage
import com.lavasec.app.foundation.dns.ProtectionUnavailableReason
import com.lavasec.app.foundation.dns.ResolverKind
import com.lavasec.app.foundation.dns.ResolverPlanUnavailableReason
import com.lavasec.app.foundation.filter.FilterAction
import com.lavasec.app.foundation.filter.FilterArtifactUnavailableReason
import com.lavasec.app.foundation.filter.FilterDecision
import com.lavasec.app.foundation.filter.FilterDecisionReason
import com.lavasec.app.foundation.health.PrimaryResolverIdentity
import com.lavasec.app.foundation.health.ResolverFallbackCoverage
import com.lavasec.app.foundation.pause.PauseDuration
import com.lavasec.app.foundation.pause.PausePolicy
import com.lavasec.app.foundation.pause.PauseState
import com.lavasec.app.foundation.pause.RuntimePauseDirective
import kotlin.coroutines.cancellation.CancellationException
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
class DnsProxyPlanningTest {
    @Test
    fun `invalid non UDP non DNS fragmented and non synthetic packets drop lazily`() {
        val harness = DnsProxyHarness()
        val engine = harness.engine()
        val packets = listOf(
            ByteArray(5),
            testDnsPacket(protocol = 6),
            testDnsPacket(destinationPort = 853),
            testDnsPacket(flagsAndFragmentOffset = 0x2000),
            testDnsPacket(destinationAddress = byteArrayOf(10, 111, 0, 2)),
        )

        packets.forEach { packet ->
            assertSame(ProxyPlan.Drop, harness.proxy.plan(packet, engine))
        }

        assertPlanningDependenciesUntouched(harness, engine)
    }

    @Test
    fun `unformable DNS drops while formable malformed DNS returns local SERVFAIL lazily`() {
        val harness = DnsProxyHarness()
        val engine = harness.engine()

        assertSame(ProxyPlan.Drop, harness.proxy.plan(testDnsPacket(ByteArray(11)), engine))

        val publication = harness.publish(
            harness.proxy.plan(
                testDnsPacket(ByteArray(12).also { it[4] = 0; it[5] = 1 }),
                engine,
            ),
        )
        val dns = publication.dnsPayload()

        assertEquals(0x8082, dns.readUInt16(2))
        assertEquals(0, dns.readUInt16(4))
        assertNull(publication.activity)
        assertTrue(publication.healthEvents.isEmpty())
        assertNull(publication.resolverQueryEvidence)
        assertPlanningDependenciesUntouched(harness, engine, expectedFenceCalls = 1)
    }

    @Test
    fun `Mullvad primary and fallback bootstrap every record shape without policy`() {
        val modes = listOf(
            ResolverKind.MULLVAD_DOH to ResolverFallbackCoverage.NONE,
            ResolverKind.DEVICE to ResolverFallbackCoverage.ENCRYPTED_DNS,
        )
        val records = listOf(
            1 to byteArrayOf(194.toByte(), 242.toByte(), 2, 2),
            28 to "2a07e340000000000000000000000002".hexBytes(),
            16 to null,
        )

        for ((primaryKind, fallbackCoverage) in modes) {
            for ((rawType, expectedAddress) in records) {
                val harness = DnsProxyHarness()
                harness.policySource.failure = IllegalStateException("bootstrap read policy")
                val context = testNetworkContext()
                val resolverPlan = testResolverPlan(
                    networkContext = context,
                    mullvadEnabled = true,
                    primaryResolverKind = primaryKind,
                    fallbackCoverage = fallbackCoverage,
                )
                val engine = harness.engine(
                    networkContext = context,
                    resolverPlan = resolverPlan,
                    epoch = testEpoch(harness.policyIdentity, resolverPlan),
                )
                val query = testDnsQuery("DnS.MuLlVaD.NeT", rawType = rawType)

                val publication = harness.publish(
                    harness.proxy.plan(testDnsPacket(query), engine),
                )
                val response = publication.dnsPayload()

                assertEquals(0x8180, response.readUInt16(2))
                assertEquals(if (expectedAddress == null) 0 else 1, response.readUInt16(6))
                if (expectedAddress != null) {
                    assertArrayEquals(
                        expectedAddress,
                        response.copyOfRange(response.size - expectedAddress.size, response.size),
                    )
                }
                assertNull(publication.activity)
                assertTrue(publication.healthEvents.isEmpty())
                assertNull(publication.resolverQueryEvidence)
                assertPlanningDependenciesUntouched(harness, engine, expectedFenceCalls = 1)
            }
        }
    }

    @Test
    fun `Mullvad bootstrap remains lazy with a mismatched unused policy snapshot`() {
        val harness = DnsProxyHarness()
        harness.policySource.snapshot = ProxyPolicySnapshot(
            identity = testPolicyIdentity(sourceIncarnation = 999L),
            activeProtectionSessionId = TEST_PROTECTION_SESSION_ID,
            pause = RuntimePauseDirective.Filtering,
            decisions = harness.decisions,
        )
        val context = testNetworkContext()
        val plan = testResolverPlan(
            networkContext = context,
            mullvadEnabled = true,
            primaryResolverKind = ResolverKind.MULLVAD_DOH,
        )
        val engine = harness.engine(
            networkContext = context,
            resolverPlan = plan,
            epoch = testEpoch(harness.policyIdentity, plan),
        )

        val publication = harness.publish(
            harness.proxy.plan(
                testDnsPacket(testDnsQuery("dns.mullvad.net")),
                engine,
            ),
        )

        assertEquals(1, publication.dnsPayload().readUInt16(6))
        assertPlanningDependenciesUntouched(harness, engine, expectedFenceCalls = 1)
    }

    @Test
    fun `engine without Mullvad capability routes its endpoint through normal policy`() {
        val harness = DnsProxyHarness()
        val engine = harness.engine()

        val plan = harness.proxy.plan(
            testDnsPacket(testDnsQuery("dns.mullvad.net")),
            engine,
        ) as ProxyPlan.Forward

        assertEquals("dns.mullvad.net", plan.request.question.normalizedDomain)
        assertEquals(1, harness.policySource.calls)
        assertEquals(1, harness.decisions.decideCalls)
        assertEquals(1, harness.eventIds.calls)
        assertEquals(1, harness.wallClock.calls)
        assertEquals(0, engine.resolveCalls)
    }

    @Test
    fun `active pause wins over block and captures authoritative session and TTL cap`() {
        val pause = RuntimePauseDirective.Paused(
            PausePolicy.start(
                nowMillis = TEST_NOW_MILLIS,
                duration = PauseDuration.FIVE_MINUTES,
                protectionSessionId = TEST_PROTECTION_SESSION_ID,
            ),
        )
        val harness = DnsProxyHarness(
            decision = FilterDecision(FilterAction.BLOCK, FilterDecisionReason.BLOCKLIST),
            pause = pause,
        )
        val engine = harness.engine(
            epoch = testEpoch(
                harness.policyIdentity,
                testResolverPlan(testNetworkContext()),
                sessionGeneration = 900L,
            ),
        )

        val plan = harness.proxy.plan(testDnsPacket(), engine) as ProxyPlan.Forward

        assertEquals(FilterDecisionReason.PAUSED_ALLOW, plan.activity.decision.reason)
        assertEquals(TEST_PROTECTION_SESSION_ID, plan.plannedProtectionSessionId)
        assertEquals(1L, plan.plannedPauseMaximumResponseTtlSeconds)
        assertTrue(plan.startedPaused)
        assertEquals(plan.activity.id, plan.request.queryId)
        assertEquals(1, harness.policySource.calls)
        assertEquals(1, harness.decisions.decideCalls)
        assertEquals(1, harness.eventIds.calls)
        assertEquals(1, harness.wallClock.calls)
        assertEquals(0, engine.resolveCalls)
    }

    @Test
    fun `pause authority is independent from engine generation for valid expired and foreign sessions`() {
        data class Case(
            val name: String,
            val pause: RuntimePauseDirective,
            val engineSession: Long,
            val expectPaused: Boolean,
        )
        val cases = listOf(
            Case(
                "valid with unrelated engine generation",
                RuntimePauseDirective.Paused(
                    PauseState(TEST_NOW_MILLIS + 60_000L, TEST_PROTECTION_SESSION_ID),
                ),
                engineSession = 700L,
                expectPaused = true,
            ),
            Case(
                "expired despite matching engine generation",
                RuntimePauseDirective.Paused(
                    PauseState(TEST_NOW_MILLIS - 1L, TEST_PROTECTION_SESSION_ID),
                ),
                engineSession = TEST_PROTECTION_SESSION_ID,
                expectPaused = false,
            ),
            Case(
                "foreign protection session despite matching engine generation",
                RuntimePauseDirective.Paused(
                    PauseState(TEST_NOW_MILLIS + 60_000L, TEST_PROTECTION_SESSION_ID + 1L),
                ),
                engineSession = TEST_PROTECTION_SESSION_ID + 1L,
                expectPaused = false,
            ),
        )

        for (case in cases) {
            val harness = DnsProxyHarness(
                decision = FilterDecision(FilterAction.BLOCK, FilterDecisionReason.BLOCKLIST),
                pause = case.pause,
            )
            val context = testNetworkContext()
            val resolverPlan = testResolverPlan(context)
            val engine = harness.engine(
                networkContext = context,
                resolverPlan = resolverPlan,
                epoch = testEpoch(
                    harness.policyIdentity,
                    resolverPlan,
                    sessionGeneration = case.engineSession,
                ),
            )

            val plan = harness.proxy.plan(testDnsPacket(), engine)

            if (case.expectPaused) {
                assertTrue(case.name, plan is ProxyPlan.Forward)
                val forward = plan as ProxyPlan.Forward
                assertEquals(FilterDecisionReason.PAUSED_ALLOW, forward.activity.decision.reason)
            } else {
                val publication = harness.publish(plan)
                assertEquals(FilterAction.BLOCK, publication.activity?.decision?.action)
                assertEquals(1, publication.dnsPayload().readUInt16(6))
            }
            assertEquals(case.name, 1, harness.policySource.calls)
            assertEquals(case.name, 1, harness.decisions.decideCalls)
            assertEquals(case.name, 1, harness.wallClock.calls)
            assertEquals(case.name, 0, engine.resolveCalls)
        }
    }

    @Test
    fun `nominally ready unavailable decision fails closed without Activity`() {
        val harness = DnsProxyHarness(decision = protectionUnavailableDecision)
        val engine = harness.engine()

        val publication = harness.publish(
            harness.proxy.plan(testDnsPacket(), engine),
        )

        assertRuntimeUnavailable(publication)
        assertEquals(1, harness.policySource.calls)
        assertEquals(1, harness.decisions.decideCalls)
        assertEquals(0, harness.eventIds.calls)
        assertEquals(0, harness.wallClock.calls)
        assertEquals(0, engine.resolveCalls)
    }

    @Test
    fun `local blocks synthesize TTL one A AAAA and NODATA with only local evidence`() {
        for ((rawType, expectedAddressBytes) in listOf(1 to 4, 28 to 16, 16 to 0)) {
            val harness = DnsProxyHarness(
                decision = FilterDecision(FilterAction.BLOCK, FilterDecisionReason.BLOCKLIST),
            )
            val engine = harness.engine()
            val query = testDnsQuery(rawType = rawType)

            val publication = harness.publish(
                harness.proxy.plan(testDnsPacket(query), engine),
            )
            val response = publication.dnsPayload()

            assertEquals(if (expectedAddressBytes == 0) 0 else 1, response.readUInt16(6))
            if (expectedAddressBytes > 0) {
                assertEquals(1L, response.readUInt32(query.size + 6))
                assertArrayEquals(ByteArray(expectedAddressBytes), response.copyOfRange(
                    response.size - expectedAddressBytes,
                    response.size,
                ))
            } else {
                assertEquals(query.size, response.size)
            }
            assertEquals(FilterAction.BLOCK, publication.activity?.decision?.action)
            assertEquals(FilterDecisionReason.BLOCKLIST, publication.activity?.decision?.reason)
            assertNull(publication.activity?.resolver)
            assertNull(publication.activity?.transport)
            assertNull(publication.activity?.latencyMillis)
            assertNull(publication.activity?.error)
            assertTrue(publication.healthEvents.isEmpty())
            assertNull(publication.resolverQueryEvidence)
            assertEquals(0, engine.resolveCalls)
        }
    }

    @Test
    fun `allow creates one exact immutable resolver request without upstream IO`() {
        val harness = DnsProxyHarness()
        val engine = harness.engine()
        val query = testDnsQuery("MiXeD.Example")
        val querySnapshot = query.copyOf()

        val plan = harness.proxy.plan(testDnsPacket(query), engine) as ProxyPlan.Forward
        query.fill(0)
        val exposedReplacement = plan.replacement.snapshot()
        exposedReplacement.fill(0)
        val stableReplacement = plan.replacement.snapshot()

        assertArrayEquals(querySnapshot, plan.request.query)
        assertEquals(
            2,
            stableReplacement.copyOfRange(
                DnsOnlyTunContract.IPV4_HEADER_BYTES + DnsOnlyTunContract.UDP_HEADER_BYTES,
                stableReplacement.size,
            ).readUInt16(2) and 0x000f,
        )
        assertEquals("mixed.example", plan.request.question.normalizedDomain)
        assertArrayEquals(
            querySnapshot.copyOfRange(12, querySnapshot.size),
            plan.request.question.questionBytes(querySnapshot),
        )
        assertSame(engine.epoch, plan.request.epoch)
        assertSame(engine.networkContext, plan.request.networkContext)
        assertEquals(plan.activity.id, plan.request.queryId)
        assertEquals(101L, plan.activity.id)
        assertEquals(TEST_NOW_MILLIS, plan.activity.timestampMillis)
        assertSame(harness.policyIdentity, plan.policyIdentity)
        assertEquals(TEST_PROTECTION_SESSION_ID, plan.plannedProtectionSessionId)
        assertFalse(plan.startedPaused)
        assertNull(plan.plannedPauseMaximumResponseTtlSeconds)
        assertEquals(0, engine.resolveCalls)
        assertEquals(0, engine.currentChecks)
        assertEquals(0, engine.fenceCalls)
        assertEquals(harness.monotonicClock.now + ResolverExecutionBudget.WHOLE_QUERY_NANOS,
            plan.request.wholeQueryDeadlineNanos)
        assertEquals(1, harness.monotonicClock.calls)
    }

    @Test
    fun `policy and decision source failures fail closed lazily while cancellation propagates`() {
        val policyFailure = DnsProxyHarness()
        val policyFailureEngine = policyFailure.engine()
        policyFailure.policySource.failure = IllegalStateException("policy failed")

        assertRuntimeUnavailable(
            policyFailure.publish(
                policyFailure.proxy.plan(testDnsPacket(), policyFailureEngine),
            ),
        )
        assertEquals(0, policyFailure.decisions.decideCalls)
        assertEquals(0, policyFailure.eventIds.calls)
        assertEquals(0, policyFailure.wallClock.calls)
        assertEquals(0, policyFailureEngine.resolveCalls)

        val decisionFailure = DnsProxyHarness()
        val decisionFailureEngine = decisionFailure.engine()
        decisionFailure.decisions.failure = IllegalStateException("decision failed")

        assertRuntimeUnavailable(
            decisionFailure.publish(
                decisionFailure.proxy.plan(testDnsPacket(), decisionFailureEngine),
            ),
        )
        assertEquals(1, decisionFailure.policySource.calls)
        assertEquals(1, decisionFailure.decisions.decideCalls)
        assertEquals(0, decisionFailure.eventIds.calls)
        assertEquals(0, decisionFailure.wallClock.calls)
        assertEquals(0, decisionFailureEngine.resolveCalls)

        val cancelledPolicy = DnsProxyHarness()
        cancelledPolicy.policySource.failure = CancellationException("cancelled policy")
        assertThrows(CancellationException::class.java) {
            cancelledPolicy.proxy.plan(testDnsPacket(), cancelledPolicy.engine())
        }

        val cancelledDecision = DnsProxyHarness()
        cancelledDecision.decisions.failure = CancellationException("cancelled decision")
        assertThrows(CancellationException::class.java) {
            cancelledDecision.proxy.plan(testDnsPacket(), cancelledDecision.engine())
        }
    }

    @Test
    fun `foreign engine proxy pairing drops before every runtime dependency`() {
        val owner = DnsProxyHarness()
        val foreign = DnsProxyHarness()
        val foreignEngine = foreign.engine()

        val plan = owner.proxy.plan(testDnsPacket(), foreignEngine)

        assertSame(ProxyPlan.Drop, plan)
        assertPlanningDependenciesUntouched(owner, foreignEngine)
        assertEquals(0, foreign.policySource.calls)
        assertEquals(0, foreign.eventIds.calls)
        assertEquals(0, foreign.wallClock.calls)
        assertEquals(0, foreign.sink.calls.get())
        assertEquals(0, foreignEngine.fenceCalls)
    }

    @Test
    fun `incoherent resolver plan identity fails closed before bootstrap or policy`() {
        data class Case(
            val name: String,
            val make: (DnsProxyHarness) -> TestReadyDnsEngine,
        )
        val cases = listOf(
            Case("network reference") { harness ->
                val context = testNetworkContext()
                val plan = testResolverPlan(context, network = testNetwork(99))
                harness.engine(
                    networkContext = context,
                    resolverPlan = plan,
                    epoch = testEpoch(harness.policyIdentity, plan),
                )
            },
            Case("primary identity") { harness ->
                val context = testNetworkContext()
                val plan = testResolverPlan(
                    context,
                    mullvadEnabled = true,
                    primaryResolverKind = ResolverKind.MULLVAD_DOH,
                )
                harness.engine(
                    networkContext = context,
                    resolverPlan = plan,
                    epoch = testEpoch(
                        harness.policyIdentity,
                        plan,
                        primaryResolverIdentity = PrimaryResolverIdentity("foreign-primary"),
                    ),
                )
            },
            Case("fallback coverage") { harness ->
                val context = testNetworkContext()
                val plan = testResolverPlan(
                    context,
                    mullvadEnabled = true,
                    fallbackCoverage = ResolverFallbackCoverage.ENCRYPTED_DNS,
                )
                harness.engine(
                    networkContext = context,
                    resolverPlan = plan,
                    epoch = testEpoch(
                        harness.policyIdentity,
                        plan,
                        fallbackCoverage = ResolverFallbackCoverage.NONE,
                    ),
                )
            },
        )

        for (case in cases) {
            val harness = DnsProxyHarness()
            val engine = case.make(harness)

            val publication = harness.publish(
                harness.proxy.plan(
                    testDnsPacket(testDnsQuery("dns.mullvad.net")),
                    engine,
                ),
            )

            assertRuntimeUnavailable(publication)
            assertEquals(case.name, 0, harness.policySource.calls)
            assertEquals(case.name, 0, harness.eventIds.calls)
            assertEquals(case.name, 0, harness.wallClock.calls)
            assertEquals(case.name, 0, engine.resolveCalls)
        }
    }

    @Test
    fun `engine network generation mismatch fails before resolver request construction`() {
        val harness = DnsProxyHarness()
        val context = testNetworkContext(networkGeneration = 17L)
        val resolverPlan = testResolverPlan(context)
        val epoch = testEpoch(
            harness.policyIdentity,
            resolverPlan,
            networkGeneration = 18L,
        )
        val engine = harness.engine(
            networkContext = context,
            resolverPlan = resolverPlan,
            epoch = epoch,
        )

        val publication = harness.publish(
            harness.proxy.plan(testDnsPacket(), engine),
        )

        assertRuntimeUnavailable(publication)
        assertEquals(0, harness.policySource.calls)
        assertEquals(0, harness.eventIds.calls)
        assertEquals(0, harness.wallClock.calls)
        assertEquals(0, engine.resolveCalls)
    }

    @Test
    fun `every policy identity constituent is matched before forwarding`() {
        val mismatches = listOf(
            testPolicyIdentity(
                configurationIdentity = testConfigurationIdentity(generation = 8L),
            ),
            testPolicyIdentity(artifactDigest = "foreign-digest"),
            testPolicyIdentity(revision = 12L),
            testPolicyIdentity(sourceIncarnation = 14L),
        )

        for (mismatch in mismatches) {
            val harness = DnsProxyHarness()
            val engine = harness.engine()
            harness.policySource.snapshot = ProxyPolicySnapshot(
                identity = mismatch,
                activeProtectionSessionId = TEST_PROTECTION_SESSION_ID,
                pause = RuntimePauseDirective.Filtering,
                decisions = harness.decisions,
            )

            val publication = harness.publish(
                harness.proxy.plan(testDnsPacket(), engine),
            )

            assertRuntimeUnavailable(publication)
            assertEquals(1, harness.policySource.calls)
            assertEquals(0, harness.decisions.decideCalls)
            assertEquals(0, harness.eventIds.calls)
            assertEquals(0, harness.wallClock.calls)
            assertEquals(0, engine.resolveCalls)
        }
    }

    @Test
    fun `engine verified policy must itself match epoch configuration digest and revision`() {
        val mismatches = listOf(
            testPolicyIdentity(
                configurationIdentity = testConfigurationIdentity(generation = 8L),
            ),
            testPolicyIdentity(artifactDigest = "foreign-digest"),
            testPolicyIdentity(revision = 12L),
        )

        for (mismatch in mismatches) {
            val harness = DnsProxyHarness(policyIdentity = mismatch)
            val context = testNetworkContext()
            val resolverPlan = testResolverPlan(context)
            val epoch = testEpoch(testPolicyIdentity(), resolverPlan)
            val engine = harness.engine(
                networkContext = context,
                resolverPlan = resolverPlan,
                epoch = epoch,
            )

            val publication = harness.publish(
                harness.proxy.plan(testDnsPacket(), engine),
            )

            assertRuntimeUnavailable(publication)
            assertEquals(1, harness.policySource.calls)
            assertEquals(0, harness.decisions.decideCalls)
            assertEquals(0, engine.resolveCalls)
        }
    }

    @Test
    fun `strict Android DNS query violations get bounded malformed SERVFAIL before policy`() {
        val malformedQueries = listOf(
            testDnsQuery(flags = 0x0900),
            testDnsQuery(questionClass = 3),
            testDnsQuery(answerCount = 1),
            testDnsQuery(authorityCount = 1),
            testDnsQuery(
                additionalRecords = testOrdinaryAdditionalRecord(),
                additionalCount = 1,
            ),
            testDnsQuery(
                additionalRecords = testOptRecord(
                    rdata = byteArrayOf(1),
                    declaredRdataLength = 2,
                ),
                additionalCount = 1,
            ),
            testDnsQuery(trailingBytes = byteArrayOf(0x55)),
        )

        for (query in malformedQueries) {
            val harness = DnsProxyHarness()
            val engine = harness.engine()

            val publication = harness.publish(
                harness.proxy.plan(testDnsPacket(query), engine),
            )

            val response = publication.dnsPayload()
            assertEquals(2, response.readUInt16(2) and 0x000f)
            assertEquals(0, response.readUInt16(4))
            assertTrue(response.size <= DnsMessage.HEADER_BYTES)
            assertNull(publication.activity)
            assertTrue(publication.healthEvents.isEmpty())
            assertEquals(0, harness.policySource.calls)
            assertEquals(0, harness.eventIds.calls)
            assertEquals(0, harness.wallClock.calls)
            assertEquals(0, engine.resolveCalls)
        }
    }

    @Test
    fun `unavailable planning applies the exact drop malformed block and SERVFAIL matrix`() {
        val harness = DnsProxyHarness()
        val artifactReason = ProtectionUnavailableReason.Artifact(
            FilterArtifactUnavailableReason.ARTIFACT_MISSING,
        )

        assertSame(
            ProxyPlan.Drop,
            harness.proxy.planUnavailable(ByteArray(3), artifactReason),
        )
        assertSame(
            ProxyPlan.Drop,
            harness.proxy.planUnavailable(testDnsPacket(ByteArray(11)), artifactReason),
        )

        val malformed = harness.publish(
            harness.proxy.planUnavailable(
                testDnsPacket(ByteArray(12).also { it[5] = 1 }),
                artifactReason,
            ),
        )
        assertEquals(2, malformed.dnsPayload().readUInt16(2) and 0x000f)
        assertTrue(malformed.healthEvents.isEmpty())
        assertNull(malformed.activity)

        val reasons = listOf(
            artifactReason,
            ProtectionUnavailableReason.ResolverPlan(
                ResolverPlanUnavailableReason.NO_USABLE_RESOLVER,
            ),
            ProtectionUnavailableReason.NetworkUnavailable,
            ProtectionUnavailableReason.RuntimeUnavailable,
        )
        for (reason in reasons) {
            val query = testDnsQuery(rawType = 1)
            val publication = harness.publish(
                harness.proxy.planUnavailable(testDnsPacket(query), reason),
            )
            val response = publication.dnsPayload()

            if (reason is ProtectionUnavailableReason.Artifact) {
                assertEquals(0, response.readUInt16(2) and 0x000f)
                assertEquals(1, response.readUInt16(6))
                assertEquals(1L, response.readUInt32(query.size + 6))
            } else {
                assertEquals(2, response.readUInt16(2) and 0x000f)
                assertEquals(0, response.readUInt16(6))
            }
            assertEquals(
                listOf(DnsHealthEvent.ProtectionUnavailable(reason)),
                publication.healthEvents,
            )
            assertNull(publication.activity)
            assertNull(publication.resolverQueryEvidence)
        }
        assertEquals(0, harness.policySource.calls)
        assertEquals(0, harness.eventIds.calls)
        assertEquals(0, harness.wallClock.calls)
        assertEquals(0, harness.monotonicClock.calls)
    }

    @Test
    fun `unavailable publication always uses its proxy fixed sink and fence pair`() {
        val retiredFence = TestPublicationFence(current = false)
        val retired = DnsProxyHarness(unavailableFence = retiredFence)
        val pending = (
            retired.proxy.planUnavailable(
                testDnsPacket(),
                ProtectionUnavailableReason.RuntimeUnavailable,
            ) as ProxyPlan.Immediate
        ).result

        assertFalse(pending.publishIfCurrent())
        assertEquals(1, retiredFence.calls)
        assertEquals(0, retired.sink.calls.get())

        val proxyA = DnsProxyHarness()
        val proxyB = DnsProxyHarness(unavailableFence = TestPublicationFence(current = false))
        val publication = proxyA.publish(
            proxyA.proxy.planUnavailable(
                testDnsPacket(),
                ProtectionUnavailableReason.NetworkUnavailable,
            ),
        )

        assertEquals(
            listOf(DnsHealthEvent.ProtectionUnavailable(
                ProtectionUnavailableReason.NetworkUnavailable,
            )),
            publication.healthEvents,
        )
        assertEquals(1, proxyA.unavailableFence.calls)
        assertEquals(1, proxyA.sink.calls.get())
        assertEquals(0, proxyB.unavailableFence.calls)
        assertEquals(0, proxyB.sink.calls.get())
    }

    private fun assertRuntimeUnavailable(publication: ProxyPublication) {
        assertEquals(2, publication.dnsPayload().readUInt16(2) and 0x000f)
        assertNull(publication.activity)
        assertEquals(runtimeUnavailableHealth, publication.healthEvents)
        assertNull(publication.resolverQueryEvidence)
    }

    private fun assertPlanningDependenciesUntouched(
        harness: DnsProxyHarness,
        engine: TestReadyDnsEngine,
        expectedFenceCalls: Int = 0,
    ) {
        assertEquals(0, harness.policySource.calls)
        assertEquals(0, harness.decisions.decideCalls)
        assertEquals(0, harness.eventIds.calls)
        assertEquals(0, harness.wallClock.calls)
        assertEquals(0, harness.monotonicClock.calls)
        assertEquals(0, engine.resolveCalls)
        assertEquals(0, engine.currentChecks)
        assertEquals(expectedFenceCalls, engine.fenceCalls)
    }

    private fun String.hexBytes(): ByteArray =
        chunked(2).map { it.toInt(16).toByte() }.toByteArray()
}
