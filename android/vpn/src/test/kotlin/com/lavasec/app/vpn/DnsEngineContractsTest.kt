package com.lavasec.app.vpn

import android.net.Network
import com.lavasec.app.foundation.dns.DnsDecisionEvent
import com.lavasec.app.foundation.dns.DnsHealthEvent
import com.lavasec.app.foundation.dns.DnsMessage
import com.lavasec.app.foundation.dns.ResolverFailureReason
import com.lavasec.app.foundation.dns.ResolverTransport
import com.lavasec.app.foundation.filter.FilterDecision
import com.lavasec.app.foundation.health.PrimaryResolverIdentity
import com.lavasec.app.foundation.health.ResolverFallbackCoverage
import com.lavasec.app.foundation.pause.RuntimePauseDirective
import com.lavasec.app.platform.ConfigurationIdentity
import java.net.InetAddress
import java.util.Collections
import java.util.concurrent.CountDownLatch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class DnsEngineContractsTest {
    @Test
    fun `network and wire contracts retain immutable snapshots`() {
        val servers = mutableListOf(InetAddress.getByName("192.0.2.53"))
        val network = network(42)
        val context = NetworkContext(network, 1, servers, isCellular = false)
        servers += InetAddress.getByName("2001:db8::53")

        assertEquals(listOf("192.0.2.53"), context.dnsServers.map { it.hostAddress })
        assertImmutable(context.dnsServers)

        val query = dnsQuery()
        val request = ResolverRequest(
            query,
            question = DnsMessage.parseQuestion(query),
            epoch = epoch(),
            networkContext = context,
        )
        query[0] = 99
        val querySnapshot = request.query
        querySnapshot[1] = 98
        assertTrue(request.query.contentEquals(dnsQuery()))

        val answerBytes = byteArrayOf(4, 5, 6)
        val events = mutableListOf(ResolverHealthEventFixture)
        val answer = ResolverResult.Answer(answerBytes, ResolverTransport.DEVICE_UDP, events)
        answerBytes[0] = 99
        answer.dnsMessage[1] = 98
        events.clear()
        assertTrue(answer.dnsMessage.contentEquals(byteArrayOf(4, 5, 6)))
        assertEquals(1, answer.healthEvents.size)
        assertImmutable(answer.healthEvents)

        val datagramBytes = byteArrayOf(7, 8)
        val datagram = DatagramReceive(datagramBytes, DatagramSource(InetAddress.getByName("192.0.2.53"), 53))
        datagramBytes[0] = 99
        datagram.body[1] = 98
        assertTrue(datagram.body.contentEquals(byteArrayOf(7, 8)))

        val failureEvents = mutableListOf(ResolverHealthEventFixture)
        val failure = ResolverResult.Failure(ResolverFailureReason.TIMEOUT, failureEvents)
        failureEvents.clear()
        assertEquals(1, failure.healthEvents.size)
        assertImmutable(failure.healthEvents)
    }

    @Test
    fun `composite epoch identity changes for every constituent`() {
        val original = epoch()
        val replacements = listOf(
            original.copy(sessionGeneration = 2),
            original.copy(configurationIdentity = ConfigurationIdentity(2, "cfg2")),
            original.copy(artifactDigest = "artifact2"),
            original.copy(policyRevision = 2),
            original.copy(networkGeneration = 2),
            original.copy(engineGeneration = 2),
            original.copy(primaryResolverIdentity = PrimaryResolverIdentity("other")),
            original.copy(fallbackCoverage = ResolverFallbackCoverage.ENCRYPTED_DNS),
        )

        replacements.forEach { replacement -> assertNotEquals(original, replacement) }
    }

    @Test
    fun `resolver request rejects a network context from another generation`() {
        val query = dnsQuery()

        assertThrows(IllegalArgumentException::class.java) {
            ResolverRequest(
                query = query,
                question = DnsMessage.parseQuestion(query),
                epoch = epoch(),
                networkContext = NetworkContext(
                    network(42),
                    networkGeneration = 2,
                    dnsServers = listOf(InetAddress.getByName("192.0.2.53")),
                    isCellular = false,
                ),
            )
        }
    }

    @Test
    fun `ready engine owns the exact immutable query context and lifecycle seams`() {
        val harness = DnsProxyHarness()
        val networkContext = testNetworkContext()
        val resolverPlan = testResolverPlan(networkContext)
        val engine = harness.engine(
            networkContext = networkContext,
            resolverPlan = resolverPlan,
            epoch = testEpoch(harness.policyIdentity, resolverPlan),
        )
        val request = ResolverRequest(
            query = testDnsQuery(),
            question = DnsMessage.parseQuestion(testDnsQuery()),
            epoch = engine.epoch,
            networkContext = engine.networkContext,
            queryId = 37L,
        )
        val terminal = ResolverResult.Failure(ResolverFailureReason.TIMEOUT)
        engine.resolverResult = terminal

        assertSame(networkContext, engine.networkContext)
        assertSame(resolverPlan, engine.resolverPlan)
        assertSame(harness.policyIdentity, engine.policyIdentity)
        assertSame(harness.proxy, engine.proxy)
        assertTrue(engine.isCurrent())
        assertSame(terminal, runBlocking { engine.resolveIfCurrent(request) })
        assertTrue(engine.completeIfCurrent { true })
        assertEquals(1, engine.resolveCalls)
        assertEquals(1, engine.currentChecks)
        assertEquals(1, engine.fenceCalls)

        engine.close()

        assertEquals(1, engine.closeCalls)
        assertFalse(engine.isCurrent())
        assertFalse(engine.completeIfCurrent { true })
    }

    @Test
    fun `policy snapshot retains verified identity and an independent protection session`() {
        val decisions = TestDecisionSource()
        val identity = testPolicyIdentity()
        val snapshot = ProxyPolicySnapshot(
            identity = identity,
            activeProtectionSessionId = 71L,
            pause = RuntimePauseDirective.Filtering,
            decisions = decisions,
        )
        val networkContext = testNetworkContext()
        val plan = testResolverPlan(networkContext)
        val epoch = testEpoch(identity, plan, sessionGeneration = 701L)

        assertSame(identity, snapshot.identity)
        assertEquals(testConfigurationIdentity(), snapshot.identity.configurationIdentity)
        assertEquals("artifact-digest", snapshot.identity.artifactDigest)
        assertEquals(11L, snapshot.identity.revision)
        assertEquals(13L, snapshot.identity.sourceIncarnation)
        assertEquals(71L, snapshot.activeProtectionSessionId)
        assertSame(RuntimePauseDirective.Filtering, snapshot.pause)
        assertSame(decisions, snapshot.decisions)
        assertNotEquals(epoch.sessionGeneration, snapshot.activeProtectionSessionId)
    }

    @Test
    fun `proxy publication owns defensive packet and health snapshots`() {
        val response = byteArrayOf(1, 2, 3)
        val health = mutableListOf<DnsHealthEvent>(ResolverHealthEventFixture)
        val publication = ProxyPublication(
            responsePacket = response,
            activity = null,
            healthEvents = health,
            resolverQueryEvidence = null,
        )

        response[0] = 9
        health.clear()
        val exposedResponse = publication.responsePacket
        exposedResponse[1] = 8

        assertTrue(publication.responsePacket.contentEquals(byteArrayOf(1, 2, 3)))
        assertEquals(listOf(ResolverHealthEventFixture), publication.healthEvents)
        assertImmutable(publication.healthEvents)
    }

    @Test
    fun `wire packet values enforce exact nonempty tunnel mtu boundaries`() {
        val oneBytePacket = byteArrayOf(0x5A)
        val mtuPacket = ByteArray(DnsOnlyTunContract.MTU) { index -> index.toByte() }

        listOf(oneBytePacket, mtuPacket).forEach { packet ->
            val expected = packet.copyOf()
            val replacement = ProxyReplacementResponse(packet)
            val publication = ProxyPublication(
                responsePacket = packet,
                activity = null,
                healthEvents = emptyList(),
                resolverQueryEvidence = null,
            )

            packet.fill(0)
            val exposedReplacement = replacement.snapshot()
            val exposedPublication = publication.responsePacket
            exposedReplacement.fill(0)
            exposedPublication.fill(0)

            assertTrue(replacement.snapshot().contentEquals(expected))
            assertTrue(publication.responsePacket.contentEquals(expected))
        }

        listOf(
            ByteArray(0),
            ByteArray(DnsOnlyTunContract.MTU + 1),
        ).forEach { invalidPacket ->
            assertThrows(IllegalArgumentException::class.java) {
                ProxyReplacementResponse(invalidPacket)
            }
            assertThrows(IllegalArgumentException::class.java) {
                ProxyPublication(
                    responsePacket = invalidPacket,
                    activity = null,
                    healthEvents = emptyList(),
                    resolverQueryEvidence = null,
                )
            }
        }
    }

    @Test
    fun `pending publication is single use for sequential and concurrent callers`() {
        val sequentialSink = RecordingPublicationSink()
        val sequential = ProxyResult.Pending(
            fence = TestPublicationFence(),
            publication = proxyPublication(),
            sink = sequentialSink,
        )

        assertTrue(sequential.publishIfCurrent())
        assertFalse(sequential.publishIfCurrent())
        assertEquals(1, sequentialSink.calls.get())

        val concurrentSink = RecordingPublicationSink()
        val concurrent = ProxyResult.Pending(
            fence = TestPublicationFence(),
            publication = proxyPublication(),
            sink = concurrentSink,
        )
        val start = CountDownLatch(1)
        val results = Collections.synchronizedList(mutableListOf<Boolean>())
        val callers = List(8) {
            Thread {
                start.await()
                results += concurrent.publishIfCurrent()
            }.apply { start() }
        }
        start.countDown()
        callers.forEach(Thread::join)

        assertEquals(1, results.count { it })
        assertEquals(7, results.count { !it })
        assertEquals(1, concurrentSink.calls.get())
    }

    @Test
    fun `pending contains pathological fences to one same-thread in-call sink attempt`() {
        var retained: (() -> Boolean)? = null
        val retainingSink = RecordingPublicationSink()
        val retaining = ProxyResult.Pending(
            fence = ProxyPublicationFence { completion ->
                retained = completion
                false
            },
            publication = proxyPublication(),
            sink = retainingSink,
        )
        assertFalse(retaining.publishIfCurrent())
        assertFalse(requireNotNull(retained).invoke())
        assertEquals(0, retainingSink.calls.get())

        val doubleSink = RecordingPublicationSink()
        val doubleCalling = ProxyResult.Pending(
            fence = ProxyPublicationFence { completion ->
                val first = completion()
                val second = completion()
                first || second
            },
            publication = proxyPublication(),
            sink = doubleSink,
        )
        assertTrue(doubleCalling.publishIfCurrent())
        assertEquals(1, doubleSink.calls.get())

        val falseSink = RecordingPublicationSink()
        val callbackThenFalse = ProxyResult.Pending(
            fence = ProxyPublicationFence { completion ->
                completion()
                false
            },
            publication = proxyPublication(),
            sink = falseSink,
        )
        assertTrue(callbackThenFalse.publishIfCurrent())
        assertEquals(1, falseSink.calls.get())

        val throwSink = RecordingPublicationSink()
        val callbackThenThrow = ProxyResult.Pending(
            fence = ProxyPublicationFence { completion ->
                completion()
                throw IllegalStateException("broken fence")
            },
            publication = proxyPublication(),
            sink = throwSink,
        )
        assertTrue(callbackThenThrow.publishIfCurrent())
        assertEquals(1, throwSink.calls.get())

        val beforeCallbackSink = RecordingPublicationSink()
        val throwingBeforeCallback = ProxyResult.Pending(
            fence = ProxyPublicationFence {
                throw IllegalStateException("retired fence failure")
            },
            publication = proxyPublication(),
            sink = beforeCallbackSink,
        )
        assertThrows(IllegalStateException::class.java) {
            throwingBeforeCallback.publishIfCurrent()
        }
        assertEquals(0, beforeCallbackSink.calls.get())
    }

    @Test
    fun `throwing trusted sink consumes the pending publication exactly once`() {
        val sink = RecordingPublicationSink().apply {
            failure = IllegalStateException("sink failure")
        }
        val pending = ProxyResult.Pending(
            fence = TestPublicationFence(),
            publication = proxyPublication(),
            sink = sink,
        )

        assertThrows(IllegalStateException::class.java) {
            pending.publishIfCurrent()
        }
        assertFalse(pending.publishIfCurrent())
        assertEquals(1, sink.calls.get())
    }

    @Test
    fun `forward plan retains exact immutable planning identity and owner token`() {
        val harness = DnsProxyHarness()
        val engine = harness.engine()
        val query = testDnsQuery()
        val datagram = checkNotNull(Ipv4UdpDnsPacket.parse(testDnsPacket(query)))
        val question = DnsMessage.parseQuestion(query)
        val request = ResolverRequest(
            query = query,
            question = question,
            epoch = engine.epoch,
            networkContext = engine.networkContext,
            queryId = 101L,
        )
        val activity = DnsDecisionEvent(
            id = 101L,
            timestampMillis = TEST_NOW_MILLIS,
            normalizedDomain = "example.com",
            decision = FilterDecision.DEFAULT_ALLOW,
        )
        val replacementBytes = testDnsPacket(testDnsQuery(flags = 0x8182))
        val replacement = ProxyReplacementResponse(replacementBytes)
        val forward = ProxyPlan.Forward.create(
            request = request,
            activity = activity,
            policyIdentity = harness.policyIdentity,
            plannedProtectionSessionId = 303L,
            plannedPauseMaximumResponseTtlSeconds = 1L,
            startedPaused = true,
            replacement = replacement,
            engine = engine,
            datagram = datagram,
            owner = harness.proxy,
        )

        query.fill(0)
        replacementBytes.fill(0)
        assertTrue(forward.request.query.contentEquals(testDnsQuery()))
        assertTrue(
            forward.replacement.snapshot().contentEquals(
                testDnsPacket(testDnsQuery(flags = 0x8182)),
            ),
        )
        assertSame(question, forward.request.question)
        assertSame(activity, forward.activity)
        assertSame(harness.policyIdentity, forward.policyIdentity)
        assertSame(engine, forward.engine)
        assertTrue(forward.datagram.isSyntheticDnsDestination)
        assertEquals(303L, forward.plannedProtectionSessionId)
        assertEquals(1L, forward.plannedPauseMaximumResponseTtlSeconds)
        assertTrue(forward.startedPaused)
        assertFalse(forward.tryConsume(DnsProxyHarness().proxy))
        assertTrue(forward.tryConsume(harness.proxy))
        assertFalse(forward.tryConsume(harness.proxy))
    }

    @Test
    fun `proxy contracts reject invalid identities ids timestamps and pause caps`() {
        assertThrows(IllegalArgumentException::class.java) {
            testPolicyIdentity(artifactDigest = " ")
        }
        assertThrows(IllegalArgumentException::class.java) {
            testPolicyIdentity(revision = -1L)
        }
        assertThrows(IllegalArgumentException::class.java) {
            testPolicyIdentity(sourceIncarnation = 0L)
        }
        assertThrows(IllegalArgumentException::class.java) {
            ProxyPolicySnapshot(
                identity = testPolicyIdentity(),
                activeProtectionSessionId = 0L,
                pause = RuntimePauseDirective.Filtering,
                decisions = TestDecisionSource(),
            )
        }

        val harness = DnsProxyHarness()
        val engine = harness.engine()
        val query = testDnsQuery()
        val datagram = checkNotNull(Ipv4UdpDnsPacket.parse(testDnsPacket(query)))
        val request = ResolverRequest(
            query = query,
            question = DnsMessage.parseQuestion(query),
            epoch = engine.epoch,
            networkContext = engine.networkContext,
            queryId = 101L,
        )
        fun activity(id: Long = 101L, timestamp: Long = TEST_NOW_MILLIS) = DnsDecisionEvent(
            id = id,
            timestampMillis = timestamp,
            normalizedDomain = "example.com",
            decision = FilterDecision.DEFAULT_ALLOW,
        )
        val replacement = ProxyReplacementResponse(
            testDnsPacket(testDnsQuery(flags = 0x8182)),
        )

        assertThrows(IllegalArgumentException::class.java) {
            ResolverRequest(
                query = query,
                question = DnsMessage.parseQuestion(query),
                epoch = engine.epoch,
                networkContext = engine.networkContext,
                queryId = -1L,
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            ProxyPlan.Forward.create(
                request,
                activity(id = -1L),
                harness.policyIdentity,
                plannedProtectionSessionId = 1L,
                plannedPauseMaximumResponseTtlSeconds = null,
                startedPaused = false,
                replacement = replacement,
                engine = engine,
                datagram = datagram,
                owner = harness.proxy,
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            ProxyPlan.Forward.create(
                request,
                activity(timestamp = -1L),
                harness.policyIdentity,
                plannedProtectionSessionId = 1L,
                plannedPauseMaximumResponseTtlSeconds = null,
                startedPaused = false,
                replacement = replacement,
                engine = engine,
                datagram = datagram,
                owner = harness.proxy,
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            ProxyPlan.Forward.create(
                request,
                activity(),
                harness.policyIdentity,
                plannedProtectionSessionId = 0L,
                plannedPauseMaximumResponseTtlSeconds = null,
                startedPaused = false,
                replacement = replacement,
                engine = engine,
                datagram = datagram,
                owner = harness.proxy,
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            ProxyPlan.Forward.create(
                request,
                activity(),
                harness.policyIdentity,
                plannedProtectionSessionId = 1L,
                plannedPauseMaximumResponseTtlSeconds = 1L,
                startedPaused = false,
                replacement = replacement,
                engine = engine,
                datagram = datagram,
                owner = harness.proxy,
            )
        }
    }

    private fun proxyPublication() = ProxyPublication(
        responsePacket = byteArrayOf(1, 2, 3),
        activity = null,
        healthEvents = emptyList(),
        resolverQueryEvidence = null,
    )

    private fun epoch() = EngineEpoch(
        sessionGeneration = 1,
        configurationIdentity = ConfigurationIdentity(1, "cfg"),
        artifactDigest = "artifact",
        policyRevision = 1,
        networkGeneration = 1,
        engineGeneration = 1,
        primaryResolverIdentity = PrimaryResolverIdentity("device-dns"),
        fallbackCoverage = ResolverFallbackCoverage.NONE,
    )

    private fun dnsQuery() = byteArrayOf(
        0, 1, 0, 0, 0, 1, 0, 0, 0, 0, 0, 0,
        7, 'e'.code.toByte(), 'x'.code.toByte(), 'a'.code.toByte(), 'm'.code.toByte(),
        'p'.code.toByte(), 'l'.code.toByte(), 'e'.code.toByte(),
        3, 'c'.code.toByte(), 'o'.code.toByte(), 'm'.code.toByte(),
        0, 0, 1, 0, 1,
    )

    private fun network(handle: Int): Network =
        Network::class.java.getDeclaredConstructor(Int::class.javaPrimitiveType)
            .apply { isAccessible = true }
            .newInstance(handle)

    @Suppress("UNCHECKED_CAST")
    private fun <T> assertImmutable(values: List<T>) {
        val mutable = values as MutableList<T>
        try {
            mutable.clear()
        } catch (_: UnsupportedOperationException) {
            return
        }
        assertFalse("contract list was mutable", true)
    }

    private companion object {
        val ResolverHealthEventFixture =
            com.lavasec.app.foundation.dns.DnsHealthEvent.ResolverRecovered(
                com.lavasec.app.foundation.dns.ResolverKind.DEVICE,
            )
    }
}
